package com.freshlink.order.domain.firstcome;

import com.freshlink.common.error.BusinessException;
import com.freshlink.order.common.error.OrderErrorCode;
import com.freshlink.order.domain.order.OrderRepository;
import com.freshlink.order.domain.order.OrderStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * 선착순 대기열 (오픈런 문서 2·4장, 11/2 이태우님과 정한 값).
 * 판매 시작 시각은 없고(점주가 선착순을 켜면 바로 줄 서기), 1인 1개, 입장권 5분, 대기 화면에 남은 수량을 보여 주고,
 * 순번 조회가 30초 끊기면 줄에서 뺀다. 남은 수량은 inventory가 gateway로 열려 있지 않아 order가 받아 응답에 담는다.
 * Redis를 못 쓰면 503 FIRST_COME_UNAVAILABLE이다
 */
@Service
public class FirstComeService {

    private static final Logger log = LoggerFactory.getLogger(FirstComeService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 1인 1개: 이 상태의 주문이 있으면 다시 줄 서거나 주문할 수 없다 (실패·만료·취소·환불된 주문은 세지 않는다) */
    static final Set<OrderStatus> HOLDING = EnumSet.of(OrderStatus.RESERVING, OrderStatus.PAYMENT_WAITING,
            OrderStatus.PAYMENT_APPROVING, OrderStatus.PAID, OrderStatus.READY_FOR_PICKUP, OrderStatus.PICKED_UP);

    private final FirstComeQueue queue;
    private final InventoryFirstComeClient inventory;
    private final OrderRepository orderRepository;
    private final FirstComeProperties properties;
    private final Clock clock;
    private final Map<QueueKey, Cached> lookups = new ConcurrentHashMap<>();

    public FirstComeService(FirstComeQueue queue, InventoryFirstComeClient inventory, OrderRepository orderRepository,
                            FirstComeProperties properties, Clock clock) {
        this.queue = queue;
        this.inventory = inventory;
        this.orderRepository = orderRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /** 줄 서기. 선착순이 아니면 404, 이미 주문했으면 409. 이미 서 있거나 입장권이 있으면 그 상태를 그대로 돌려준다 */
    public QueueStatusResponse join(String userId, QueueKey key) {
        FirstComeLookup lookup = requireFirstCome(key);
        if (alreadyOrdered(userId, key)) {
            throw new BusinessException(OrderErrorCode.FIRST_COME_ALREADY_ORDERED);
        }
        Instant now = clock.instant();
        return redis(() -> {
            if (queue.ticket(key, userId).isEmpty()) {
                queue.join(key, userId, now);
            }
            return status(userId, key, lookup, now);
        });
    }

    /** 내 순번 (대기 화면이 2~3초마다 부른다). 부를 때마다 "아직 보고 있음"으로 기록한다 */
    public QueueStatusResponse me(String userId, QueueKey key) {
        FirstComeLookup lookup = requireFirstCome(key);
        Instant now = clock.instant();
        return redis(() -> {
            if (queue.rank(key, userId) != null) {
                queue.touch(key, userId, now);
            }
            return status(userId, key, lookup, now);
        });
    }

    /** 그만 기다리기. 줄에 없어도 성공으로 본다 */
    public void leave(String userId, QueueKey key) {
        redis(() -> {
            queue.leave(key, userId);
            return null;
        });
    }

    /**
     * 남은 수량 (짧게 파드 메모리에 둔다). 줄 선 사람이 2~3초마다 물어도 inventory에는 상품마다 remainingCacheTtl에 한 번만 간다.
     * 알 수 없으면(타임아웃) 남겨 두지 않는다
     */
    public FirstComeLookup lookup(QueueKey key) {
        Instant now = clock.instant();
        Cached cached = lookups.get(key);
        if (cached != null && now.isBefore(cached.until())) {
            return cached.lookup();
        }
        FirstComeLookup fresh = inventory.lookup(key.storeId(), key.productId());
        if (fresh.kind() != FirstComeLookup.Kind.UNKNOWN) {
            lookups.put(key, new Cached(fresh, now.plus(properties.remainingCacheTtl())));
        }
        return fresh;
    }

    /** 스케줄러가 입장 처리 직전에 부른다. 캐시를 거치지 않고 inventory에 물어 캐시도 새로 채운다 */
    public FirstComeLookup refresh(QueueKey key) {
        lookups.remove(key);
        return lookup(key);
    }

    boolean alreadyOrdered(String userId, QueueKey key) {
        return orderRepository.existsByCustomerIdAndStoreIdAndProductIdAndStatusIn(userId, key.storeId(),
                key.productId(), HOLDING);
    }

    private FirstComeLookup requireFirstCome(QueueKey key) {
        FirstComeLookup lookup = lookup(key);
        return switch (lookup.kind()) {
            case FIRST_COME -> lookup;
            case NOT_FIRST_COME -> throw new BusinessException(OrderErrorCode.NOT_FIRST_COME);
            case UNKNOWN -> throw new BusinessException(OrderErrorCode.FIRST_COME_UNAVAILABLE);
        };
    }

    private QueueStatusResponse status(String userId, QueueKey key, FirstComeLookup lookup, Instant now) {
        long waiting = queue.waiting(key);
        OffsetDateTime serverTime = now.atZone(KST).toOffsetDateTime();
        Optional<Instant> ticket = queue.ticket(key, userId);
        if (ticket.isPresent()) {
            return new QueueStatusResponse(key.storeId(), key.productId(), QueueStatus.ADMITTED, null, null, waiting,
                    lookup.remaining(), ticket.get().atZone(KST).toOffsetDateTime(), serverTime);
        }
        Long rank = queue.rank(key, userId);
        if (rank == null) {
            return new QueueStatusResponse(key.storeId(), key.productId(), QueueStatus.NOT_IN_QUEUE, null, null,
                    waiting, lookup.remaining(), null, serverTime);
        }
        boolean soldOut = lookup.remaining() != null && lookup.remaining() == 0 && queue.activeTickets(key, now) == 0;
        return new QueueStatusResponse(key.storeId(), key.productId(),
                soldOut ? QueueStatus.SOLD_OUT : QueueStatus.WAITING, rank + 1, rank, waiting, lookup.remaining(),
                null, serverTime);
    }

    private <T> T redis(java.util.function.Supplier<T> work) {
        try {
            return work.get();
        } catch (DataAccessException e) {
            log.warn("선착순 대기열 Redis 호출 실패 cause={}", e.getClass().getSimpleName());
            throw new BusinessException(OrderErrorCode.FIRST_COME_UNAVAILABLE);
        }
    }

    private record Cached(FirstComeLookup lookup, Instant until) {
    }
}
