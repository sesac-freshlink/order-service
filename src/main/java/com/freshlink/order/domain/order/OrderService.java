package com.freshlink.order.domain.order;

import com.freshlink.common.error.BusinessException;
import com.freshlink.common.message.command.ReserveMessage;
import com.freshlink.common.outbox.OutboxWriter;
import com.freshlink.order.common.error.OrderErrorCode;
import com.freshlink.order.domain.payment.Payment;
import com.freshlink.order.domain.payment.PaymentRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final OutboxWriter outboxWriter;
    private final TransactionTemplate transactionTemplate;
    private final String commandsQueue;
    private final Clock clock;

    public OrderService(OrderRepository orderRepository, PaymentRepository paymentRepository,
                        OutboxWriter outboxWriter, TransactionTemplate transactionTemplate,
                        @Value("${app.queue.commands}") String commandsQueue, Clock clock) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.outboxWriter = outboxWriter;
        this.transactionTemplate = transactionTemplate;
        this.commandsQueue = commandsQueue;
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

    /** OR-02 응답: 주문 + 결제 내역(승인 시작 전이면 null) + 서버 현재 시각 */
    @Transactional(readOnly = true)
    public OrderResponse getDetail(String customerId, Long orderId) {
        Order order = get(customerId, orderId);
        Payment payment = paymentRepository.findByOrderId(order.getId()).orElse(null);
        return OrderResponse.from(order, payment, OffsetDateTime.now(clock));
    }

    /** OR-04 점주 신규 주문 목록: 그 점포의 PAID 주문, 결제 완료 시각 최신순 (페이지 없음) */
    @Transactional(readOnly = true)
    public List<Order> listPaidForStore(Long storeId) {
        return orderRepository.findByStoreIdAndStatusOrderByPaidAtDescIdDesc(storeId, OrderStatus.PAID);
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
