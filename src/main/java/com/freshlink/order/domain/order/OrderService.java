package com.freshlink.order.domain.order;

import com.freshlink.common.error.BusinessException;
import com.freshlink.common.message.command.ReserveMessage;
import com.freshlink.common.message.notification.NotificationMessage;
import com.freshlink.common.outbox.OutboxWriter;
import com.freshlink.order.common.error.OrderErrorCode;
import com.freshlink.order.domain.payment.Payment;
import com.freshlink.order.domain.payment.PaymentRepository;
import com.freshlink.order.domain.refund.Refund;
import com.freshlink.order.domain.refund.RefundRepository;
import com.freshlink.order.domain.refund.RefundStatus;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 픽업 대기: 결제 완료와 픽업 준비 완료 (N-02) */
    private static final Set<OrderStatus> PICKUP_WAITING = EnumSet.of(OrderStatus.PAID, OrderStatus.READY_FOR_PICKUP);

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final OutboxWriter outboxWriter;
    private final TransactionTemplate transactionTemplate;
    private final String commandsQueue;
    private final String notificationsQueue;
    private final Clock clock;

    public OrderService(OrderRepository orderRepository, PaymentRepository paymentRepository,
                        RefundRepository refundRepository, OutboxWriter outboxWriter,
                        TransactionTemplate transactionTemplate,
                        @Value("${app.queue.commands}") String commandsQueue,
                        @Value("${app.queue.notifications}") String notificationsQueue, Clock clock) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.outboxWriter = outboxWriter;
        this.transactionTemplate = transactionTemplate;
        this.commandsQueue = commandsQueue;
        this.notificationsQueue = notificationsQueue;
        this.clock = clock;
    }

    /**
     * OR-01 주문 생성. 같은 (고객, 키) 재요청은 새로 만들지 않는다.
     * 바깥에 트랜잭션을 두지 않는다: 동시 요청이 유니크 키에 걸려도 롤백이 끝난 뒤 새 조회로 먼저 만든 주문을 읽기 위해서다
     */
    public CreateOrderResult create(String customerId, String key, CreateOrderRequest req) {
        Optional<Order> found = orderRepository.findByCustomerIdAndRequestId(customerId, key);
        if (found.isPresent()) {
            return replay(found.get(), req);
        }
        try {
            Order order = transactionTemplate.execute(status -> insertWithOutbox(customerId, key, req));
            log.info("주문 생성 orderId={} storeId={} created=true", order.getId(), order.getStoreId());
            return new CreateOrderResult(order, true);
        } catch (DataIntegrityViolationException e) {
            // 동시 요청이 먼저 커밋했다. 다른 제약 위반이면 그대로 던진다
            Order existing = orderRepository.findByCustomerIdAndRequestId(customerId, key)
                    .orElseThrow(() -> e);
            return replay(existing, req);
        }
    }

    /** OR-02 주문 조회. 없으면 404, 남의 주문이면 403 (명세 순서) */
    @Transactional(readOnly = true)
    public Order get(String customerId, Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
        if (!order.getCustomerId().equals(customerId)) {
            throw new BusinessException(OrderErrorCode.ORDER_ACCESS_DENIED);
        }
        return order;
    }

    /** OR-02 응답: 주문 + 결제 내역(승인 시작 전이면 null) + 마지막 환불 시도(없으면 null, A8) + 서버 현재 시각 */
    @Transactional(readOnly = true)
    public OrderResponse getDetail(String customerId, Long orderId) {
        Order order = get(customerId, orderId);
        Payment payment = paymentRepository.findByOrderId(order.getId()).orElse(null);
        Refund refund = refundRepository.findTopByOrderIdOrderByAttemptNoDesc(order.getId()).orElse(null);
        return OrderResponse.from(order, payment, refund, OffsetDateTime.now(clock));
    }

    /**
     * OR-04 점주 신규 주문 목록: 그 점포의 픽업 대기(결제 완료·픽업 준비 완료, 환불 NONE) 주문, 결제 완료 시각 최신순 (페이지 없음).
     * 환불이 시작된 주문은 A6 취소·환불 구분에 나온다
     */
    @Transactional(readOnly = true)
    public List<Order> listPaidForStore(Long storeId) {
        return orderRepository.findByStoreIdAndStatusInAndRefundStatusOrderByPaidAtDescIdDesc(storeId, PICKUP_WAITING,
                RefundStatus.NONE);
    }

    /**
     * N-02 점주 픽업 준비 완료. 행을 잠근 채 확인해 같은 주문을 두 번 누르거나 기한 스케줄러와 겹쳐도 한 번만 바뀐다.
     * 없으면 404, 다른 점포면 403, 이미 준비 완료면 그대로 돌려준다(알림도 다시 보내지 않는다),
     * 결제 완료·환불 NONE이 아니면 409(환불이 시작된 주문은 준비하지 않는다), 기한이 지났으면 409 (스케줄러가 아직 안 돌았어도).
     * READY_FOR_PICKUP과 고객 픽업 준비 완료 알림(NotificationMessage.pickupReady) Outbox를 한 트랜잭션에 저장한다
     */
    @Transactional
    public Order markReadyForPickup(Long storeId, Long orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
        if (!order.getStoreId().equals(storeId)) {
            throw new BusinessException(OrderErrorCode.STORE_ORDER_ACCESS_DENIED);
        }
        if (order.getStatus() == OrderStatus.READY_FOR_PICKUP) {
            return order;
        }
        if (order.getStatus() != OrderStatus.PAID || order.getRefundStatus() != RefundStatus.NONE) {
            throw new BusinessException(OrderErrorCode.ORDER_NOT_READYABLE);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (order.isPickupDeadlinePassed(now)) {
            throw new BusinessException(OrderErrorCode.PICKUP_DEADLINE_PASSED);
        }
        order.markReadyForPickup(now);
        outboxWriter.save(notificationsQueue, NotificationMessage.pickupReady(order.getStoreId(), order.getStoreName(),
                order.getCustomerId(), order.getId(), order.getOrderNo(), order.getProductId(), order.getProductName(),
                order.getQuantity()));
        log.info("픽업 준비 완료 orderId={} storeId={}", orderId, storeId);
        return order;
    }

    /**
     * A5 점주 픽업 확인. 행을 잠근 채 확인해 같은 주문을 두 번 누르거나 기한 스케줄러·환불 요청과 겹쳐도 한 번만 바뀐다.
     * 없으면 404, 다른 점포면 403, 픽업 준비 완료·환불 NONE이 아니면 409(준비 완료를 먼저 거친다, N-02), 기한이 지났으면 409
     */
    @Transactional
    public Order pickUp(Long storeId, Long orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
        if (!order.getStoreId().equals(storeId)) {
            throw new BusinessException(OrderErrorCode.STORE_ORDER_ACCESS_DENIED);
        }
        if (order.getStatus() != OrderStatus.READY_FOR_PICKUP || order.getRefundStatus() != RefundStatus.NONE) {
            throw new BusinessException(OrderErrorCode.ORDER_NOT_PICKABLE);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (order.isPickupDeadlinePassed(now)) {
            throw new BusinessException(OrderErrorCode.PICKUP_DEADLINE_PASSED);
        }
        order.pickUp(now);
        log.info("픽업 완료 orderId={} storeId={}", orderId, storeId);
        return order;
    }

    /** OR-04 응답: 목록의 결제 내역은 쿼리 한 번으로 읽는다 */
    @Transactional(readOnly = true)
    public List<OwnerOrderResponse> listPaidForStoreWithPayment(Long storeId) {
        List<Order> orders = listPaidForStore(storeId);
        if (orders.isEmpty()) {
            return List.of();
        }
        Map<Long, Payment> payments = paymentRepository.findByOrderIdIn(orders.stream().map(Order::getId).toList())
                .stream().collect(Collectors.toMap(Payment::getOrderId, Function.identity()));
        return orders.stream().map(o -> OwnerOrderResponse.from(o, payments.get(o.getId()))).toList();
    }

    /** A3 고객 주문 내역: 본인 주문을 구분별·기간별로, 주문일시 최신순 */
    @Transactional(readOnly = true)
    public PageResponse<CustomerOrderSummaryResponse> listForCustomer(String customerId, CustomerOrderTab tab,
                                                                     OrderPeriod period, OrderPageRequest paging) {
        PageRequest pageable = PageRequest.of(paging.page(), paging.size(),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        return PageResponse.of(orderRepository.findForCustomer(customerId, tab.statuses(), OrderStatus.PAID,
                        tab.paidRefundStatuses(), period.start(), period.end(), pageable),
                CustomerOrderSummaryResponse::from);
    }

    /** A6 점주 주문 목록: 그 점포 주문을 구분별·기간별로, 결제 내역과 마지막 환불 시도는 쿼리 한 번씩으로 붙인다 */
    @Transactional(readOnly = true)
    public PageResponse<OwnerOrderResponse> listForStore(Long storeId, OwnerOrderTab tab, OrderPeriod period,
                                                         OrderPageRequest paging) {
        if (tab.statuses().isEmpty()) {
            return PageResponse.empty(paging.page(), paging.size());
        }
        Page<Order> page = orderRepository.findForStore(storeId, tab.statuses(), OrderStatus.PAID,
                tab.paidRefundStatuses(), period.start(), period.end(),
                PageRequest.of(paging.page(), paging.size(), tab.sort()));
        if (page.isEmpty()) {
            return PageResponse.of(page, o -> OwnerOrderResponse.from(o, null));
        }
        List<Long> ids = page.getContent().stream().map(Order::getId).toList();
        Map<Long, Payment> payments = paymentRepository.findByOrderIdIn(ids)
                .stream().collect(Collectors.toMap(Payment::getOrderId, Function.identity()));
        Map<Long, Refund> refunds = refundRepository.findByOrderIdIn(ids).stream()
                .collect(Collectors.toMap(Refund::getOrderId, Function.identity(),
                        (a, b) -> a.getAttemptNo() >= b.getAttemptNo() ? a : b));
        return PageResponse.of(page, o -> OwnerOrderResponse.from(o, payments.get(o.getId()), refunds.get(o.getId())));
    }

    /** 주문 저장, 주문번호 채우기, RESERVE 쪽지 outbox 저장을 한 트랜잭션으로 한다 */
    private Order insertWithOutbox(String customerId, String key, CreateOrderRequest req) {
        Order order = orderRepository.saveAndFlush(Order.create(
                customerId, key, req.storeId(), req.storeName(), req.productId(), req.quantity()));
        LocalDate date = order.getCreatedAt().atZoneSameInstant(KST).toLocalDate();
        order.assignOrderNo(Order.formatOrderNo(order.getId(), date));
        outboxWriter.save(commandsQueue,
                ReserveMessage.of(order.getId(), order.getStoreId(), order.getProductId(), order.getQuantity()));
        return order;
    }

    private CreateOrderResult replay(Order order, CreateOrderRequest req) {
        if (!order.hasSameContent(req.storeId(), req.productId(), req.quantity())) {
            throw new BusinessException(OrderErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        log.info("주문 재요청 orderId={} created=false", order.getId());
        return new CreateOrderResult(order, false);
    }
}
