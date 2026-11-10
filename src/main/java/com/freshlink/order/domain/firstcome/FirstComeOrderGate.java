package com.freshlink.order.domain.firstcome;

import com.freshlink.common.error.BusinessException;
import com.freshlink.order.common.error.OrderErrorCode;
import com.freshlink.order.domain.order.CreateOrderRequest;
import com.freshlink.order.domain.order.OrderRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * 주문 생성(OR-01) 앞의 선착순 검사. 선착순 상품이면 수량 1개, 입장권으로 산 같은 상품 주문이 없을 것, 입장권이 있을 것.
 * 입장권은 주문을 만들기 직전에 쓰고(GETDEL), 주문을 만들지 못하면 되돌린다.
 * <ul>
 *   <li>같은 Idempotency-Key 재요청은 검사하지 않는다 (이미 만든 주문을 그대로 돌려준다)</li>
 *   <li>inventory를 알 수 없으면(타임아웃) 일반 주문처럼 통과시킨다. 한도는 inventory가 RESERVE에서 다시 막아
 *       (#61, 한도를 넘는 예약은 RESERVE_FAILED) 한도 넘게 팔리지 않는다. 대기열은 DB 앞에서 몰림을 줄이는 장치다</li>
 * </ul>
 */
@Component
public class FirstComeOrderGate {

    private static final Logger log = LoggerFactory.getLogger(FirstComeOrderGate.class);

    private final FirstComeService firstComeService;
    private final FirstComeQueue queue;
    private final OrderRepository orderRepository;
    private final FirstComeProperties properties;
    private final Clock clock;

    public FirstComeOrderGate(FirstComeService firstComeService, FirstComeQueue queue, OrderRepository orderRepository,
                              FirstComeProperties properties, Clock clock) {
        this.firstComeService = firstComeService;
        this.queue = queue;
        this.orderRepository = orderRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 주문을 만들어도 되는지 보고 입장권을 쓴다. 선착순이 아니거나 재요청이면 null.
     * 돌려준 값은 주문을 만들지 못했을 때 {@link #release}로 되돌린다
     */
    public Admission admit(String userId, String idempotencyKey, CreateOrderRequest request) {
        if (orderRepository.findByCustomerIdAndRequestId(userId, idempotencyKey).isPresent()) {
            return null;
        }
        QueueKey key = new QueueKey(request.storeId(), request.productId());
        if (!firstComeService.lookup(key).isFirstCome()) {
            return null;
        }
        if (request.quantity() != 1) {
            throw new BusinessException(OrderErrorCode.FIRST_COME_QUANTITY_LIMIT);
        }
        if (request.clearanceQuantity() > 0) {
            // 선착순 상품은 마감세일을 만들지 않는다 (마감세일 함께 사기, 11/6 이태우·김경우)
            throw new BusinessException(OrderErrorCode.FIRST_COME_CLEARANCE_NOT_ALLOWED);
        }
        Optional<Instant> ticket;
        try {
            if (firstComeService.alreadyOrdered(userId, key)) {
                throw new BusinessException(OrderErrorCode.FIRST_COME_ALREADY_ORDERED);
            }
            ticket = queue.takeTicket(key, userId, clock.instant(), properties.reserveGrace());
        } catch (DataAccessException e) {
            log.warn("선착순 입장권 확인 실패 storeId={} productId={} cause={}", key.storeId(), key.productId(),
                    e.getClass().getSimpleName());
            throw new BusinessException(OrderErrorCode.FIRST_COME_UNAVAILABLE);
        }
        if (ticket.isEmpty()) {
            throw new BusinessException(OrderErrorCode.FIRST_COME_TICKET_REQUIRED);
        }
        log.info("선착순 입장권으로 주문 storeId={} productId={}", key.storeId(), key.productId());
        return new Admission(key, userId, ticket.get());
    }

    /** 입장권으로 주문을 만들었으면 그날(KST) 산 사람 목록에 적는다(1인 1개). Redis 오류는 삼킨다 (그때는 다시 줄 설 수 있다) */
    public void complete(Admission admission, Long orderId) {
        if (admission == null) {
            return;
        }
        try {
            queue.addBuyer(admission.key(), FirstComeService.salesDay(clock.instant()), admission.userId(), orderId);
        } catch (DataAccessException e) {
            log.warn("선착순 산 사람 기록 실패 storeId={} productId={} orderId={}", admission.key().storeId(),
                    admission.key().productId(), orderId);
        }
    }

    /** 주문을 만들지 못했으면 입장권을 남은 시간만큼 되돌린다. Redis 오류는 삼킨다 (입장권은 원래 시간에 끝난다) */
    public void release(Admission admission) {
        if (admission == null) {
            return;
        }
        try {
            queue.restoreTicket(admission.key(), admission.userId(), admission.ticketExpiresAt(), clock.instant());
        } catch (DataAccessException e) {
            log.warn("선착순 입장권 되돌리기 실패 storeId={} productId={}", admission.key().storeId(),
                    admission.key().productId());
        }
    }

    public record Admission(QueueKey key, String userId, Instant ticketExpiresAt) {
    }
}
