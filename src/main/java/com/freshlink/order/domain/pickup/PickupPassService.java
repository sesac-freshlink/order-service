package com.freshlink.order.domain.pickup;

import com.freshlink.common.error.BusinessException;
import com.freshlink.order.common.error.OrderErrorCode;
import com.freshlink.order.domain.order.Order;
import com.freshlink.order.domain.order.OrderRepository;
import com.freshlink.order.domain.order.OrderService;
import com.freshlink.order.domain.order.OrderStatus;
import com.freshlink.order.domain.refund.RefundStatus;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * 픽업 QR (추가 명세 A16): 고객이 표(QR)·6자리 코드를 만들고, 점주 핸드폰이 찍으면 점주 컴퓨터에 그 고객의 이 점포
 * 미픽업 주문이 뜨고, 점주가 고른 픽업 준비 완료 주문만 건마다 픽업 완료로 바꾼다.
 * 픽업 규칙은 A5 그대로다(OrderService.pickUp: 준비 완료·환불 NONE·기한 이내). Redis를 못 쓰면 503 PICKUP_PASS_UNAVAILABLE
 */
@Service
public class PickupPassService {

    /** 미픽업 주문: 결제 완료·픽업 준비 완료이고 환불 NONE (고객 주문 내역 PICKUP_WAITING과 같다) */
    static final Set<OrderStatus> WAITING = EnumSet.of(OrderStatus.PAID, OrderStatus.READY_FOR_PICKUP);
    /** 한 번에 승인할 수 있는 주문 수 */
    public static final int MAX_APPROVE = 20;
    private static final Pattern CODE = Pattern.compile("\\d{6}");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Logger log = LoggerFactory.getLogger(PickupPassService.class);

    private final PickupPassStore store;
    private final PickupScanStream stream;
    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final Clock clock;

    public PickupPassService(PickupPassStore store, PickupScanStream stream, OrderRepository orderRepository,
                             OrderService orderService, Clock clock) {
        this.store = store;
        this.stream = stream;
        this.orderRepository = orderRepository;
        this.orderService = orderService;
        this.clock = clock;
    }

    /** 고객 QR 만들기. 기한이 남은 미픽업 주문이 없으면 409. 예전 QR은 지운다 */
    public PickupPassResponse issue(String customerId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long count = orderRepository
                .findByCustomerIdAndStatusInAndRefundStatusOrderByPaidAtAscIdAsc(customerId, WAITING, RefundStatus.NONE)
                .stream().filter(o -> !o.isPickupDeadlinePassed(now)).count();
        if (count == 0) {
            throw new BusinessException(OrderErrorCode.NO_PICKUP_WAITING_ORDER);
        }
        PickupPassStore.Pass pass = redis(() -> store.issue(customerId));
        log.info("픽업 QR 발급 customerId={} orders={}", customerId, count);
        return new PickupPassResponse(pass.token(), pass.code(),
                now.plus(PickupPassStore.TTL).atZoneSameInstant(KST).toOffsetDateTime(), (int) count);
    }

    /** 점주 핸드폰이 QR을 찍음: 표를 확인하고 이 점포의 열린 컴퓨터 화면에 알린다. 표가 없거나 만료면 404 */
    public PickupScanResponse scan(Long storeId, String token) {
        redis(() -> store.customerOf(token))
                .orElseThrow(() -> new BusinessException(OrderErrorCode.PICKUP_PASS_NOT_FOUND));
        int screens = stream.notifyScan(storeId, token);
        log.info("픽업 QR 스캔 storeId={} screens={}", storeId, screens);
        return new PickupScanResponse(screens);
    }

    /**
     * 표 또는 6자리 코드로 그 고객의 이 점포 미픽업 주문(기한 이내). 픽업 준비 완료만 고를 수 있다(selectable).
     * 코드가 틀리면 점포마다 센다. 1분에 10번 넘게 틀리면 429
     */
    public PickupPassOrdersResponse orders(Long storeId, String key) {
        String token = resolveToken(storeId, key);
        String customerId = redis(() -> store.customerOf(token))
                .orElseThrow(() -> new BusinessException(OrderErrorCode.PICKUP_PASS_NOT_FOUND));
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<PickupPassOrdersResponse.Item> items = orderRepository
                .findByCustomerIdAndStoreIdAndStatusInAndRefundStatusOrderByPaidAtAscIdAsc(customerId, storeId, WAITING,
                        RefundStatus.NONE)
                .stream()
                .filter(o -> !o.isPickupDeadlinePassed(now))
                .map(PickupPassOrdersResponse.Item::from)
                .toList();
        return new PickupPassOrdersResponse(token, items);
    }

    /**
     * 고른 주문을 건마다 따로 픽업 완료로 바꾼다(최대 20건). 표의 고객·이 점포 주문만 된다.
     * 한 건이 안 돼도 나머지는 그대로 하고 건마다 결과를 돌려준다
     */
    public PickupApproveResponse approve(Long storeId, String token, List<Long> orderIds) {
        String customerId = redis(() -> store.customerOf(token))
                .orElseThrow(() -> new BusinessException(OrderErrorCode.PICKUP_PASS_NOT_FOUND));
        List<PickupApproveResponse.Result> results = new ArrayList<>();
        for (Long orderId : new LinkedHashSet<>(orderIds)) {
            results.add(new PickupApproveResponse.Result(orderId, approveOne(storeId, customerId, orderId)));
        }
        log.info("픽업 QR 승인 storeId={} customerId={} results={}", storeId, customerId, results);
        return new PickupApproveResponse(results);
    }

    private PickupApproveResponse.Outcome approveOne(Long storeId, String customerId, Long orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null || !order.getCustomerId().equals(customerId) || !order.getStoreId().equals(storeId)) {
            return PickupApproveResponse.Outcome.NOT_FOUND;
        }
        try {
            orderService.pickUp(storeId, orderId);
            return PickupApproveResponse.Outcome.PICKED_UP;
        } catch (BusinessException e) {
            if (e.errorCode() == OrderErrorCode.PICKUP_DEADLINE_PASSED) {
                return PickupApproveResponse.Outcome.DEADLINE_PASSED;
            }
            if (e.errorCode() == OrderErrorCode.ORDER_NOT_PICKABLE) {
                return PickupApproveResponse.Outcome.NOT_PICKABLE;
            }
            return PickupApproveResponse.Outcome.NOT_FOUND;
        }
    }

    /** 6자리 숫자면 코드로 보고 표를 찾는다. 틀린 코드는 점포마다 센다 */
    private String resolveToken(Long storeId, String key) {
        if (!CODE.matcher(key).matches()) {
            return key;
        }
        if (redis(() -> store.codeLocked(storeId))) {
            throw new BusinessException(OrderErrorCode.PICKUP_CODE_TOO_MANY_ATTEMPTS);
        }
        return redis(() -> store.tokenOfCode(key)).orElseGet(() -> {
            redis(() -> {
                store.recordCodeFail(storeId);
                return null;
            });
            throw new BusinessException(OrderErrorCode.PICKUP_PASS_NOT_FOUND);
        });
    }

    private static <T> T redis(Supplier<T> call) {
        try {
            return call.get();
        } catch (DataAccessException e) {
            log.warn("픽업 QR Redis 오류 {}", e.getClass().getSimpleName());
            throw new BusinessException(OrderErrorCode.PICKUP_PASS_UNAVAILABLE);
        }
    }
}
