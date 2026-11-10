package com.freshlink.order.domain.firstcome;

import com.freshlink.common.error.BusinessException;
import com.freshlink.order.common.error.OrderErrorCode;
import com.freshlink.order.domain.order.OrderRepository;
import com.freshlink.order.domain.order.OrderStatus;
import com.freshlink.order.domain.refund.RefundStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
    /** 남은 수량을 파드 메모리에 두는 상품 수 상한. 점포·상품 번호는 요청에서 오므로 끝없이 늘지 않게 막는다 */
    static final int MAX_CACHED_LOOKUPS = 1_000;
    /**
     * 1인 1개(하루, KST): 그날 입장권으로 만든 주문이 이 상태이고 환불이 시작되지 않았으면 그날은 다시 줄 서거나 주문할 수 없다.
     * 다음 날은 다시 살 수 있다(11/2 결정).
     * 픽업 기한이 지난 주문(PICKUP_EXPIRED)도 결제는 끝나 환불 없이 판매된 것이라 센다.
     * 실패·만료·취소·환불(진행 중 포함)된 주문은 세지 않는다. 입장권 없이 산 예전 일반 주문도 세지 않는다
     */
    static final Set<OrderStatus> HOLDING = EnumSet.of(OrderStatus.RESERVING, OrderStatus.PAYMENT_WAITING,
            OrderStatus.PAYMENT_APPROVING, OrderStatus.PAID, OrderStatus.PICKUP_REQUESTED, OrderStatus.READY_FOR_PICKUP,
            OrderStatus.PICKED_UP,
            OrderStatus.PICKUP_EXPIRED);

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

    /** 줄 서기. 선착순이 아니면 404, 입장권으로 이미 주문했으면 409. 이미 서 있거나 입장권이 있으면 그 상태를 그대로 돌려준다 */
    public QueueStatusResponse join(String userId, QueueKey key) {
        FirstComeLookup lookup = requireFirstCome(key);
        if (redis(() -> alreadyOrdered(userId, key))) {
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
     * 알 수 없으면(타임아웃) 남겨 두지 않는다. 상품 수가 MAX_CACHED_LOOKUPS를 넘으면 시간이 지난 것부터 지우고,
     * 그래도 넘으면 모두 비운다 (다음 조회 때 inventory에 다시 묻는다)
     */
    public FirstComeLookup lookup(QueueKey key) {
        Instant now = clock.instant();
        Cached cached = lookups.get(key);
        if (cached != null && now.isBefore(cached.until())) {
            return cached.lookup();
        }
        FirstComeLookup fresh = inventory.lookup(key.storeId(), key.productId());
        if (fresh.kind() == FirstComeLookup.Kind.UNKNOWN) {
            lookups.remove(key);
            return fresh;
        }
        lookups.put(key, new Cached(fresh, now.plus(properties.remainingCacheTtl())));
        if (lookups.size() > MAX_CACHED_LOOKUPS) {
            lookups.values().removeIf(entry -> !now.isBefore(entry.until()));
            if (lookups.size() > MAX_CACHED_LOOKUPS) {
                lookups.clear();
            }
        }
        return fresh;
    }

    /** 테스트용: 지금 파드 메모리에 둔 상품 수 */
    int cachedLookups() {
        return lookups.size();
    }

    /** 스케줄러가 입장 처리 직전에 부른다. 캐시를 거치지 않고 inventory에 물어 캐시도 새로 채운다 */
    public FirstComeLookup refresh(QueueKey key) {
        lookups.remove(key);
        return lookup(key);
    }

    /**
     * 이 사람이 오늘(KST) 선착순으로 이미 샀는지. 오늘 입장권으로 만든 주문(Redis fc:bought)을 DB에서 다시 읽어,
     * 실패·취소됐거나 환불이 시작됐으면 목록에서 빼고 false (다시 살 수 있다)
     */
    boolean alreadyOrdered(String userId, QueueKey key) {
        LocalDate today = salesDay(clock.instant());
        Long orderId = queue.buyerOrderId(key, today, userId);
        if (orderId == null) {
            return false;
        }
        boolean holding = orderRepository.findById(orderId)
                .filter(order -> HOLDING.contains(order.getStatus()) && order.getRefundStatus() == RefundStatus.NONE)
                .isPresent();
        if (!holding) {
            queue.removeBuyer(key, today, userId);
        }
        return holding;
    }

    /** 1인 1개를 세는 날 (KST) */
    static LocalDate salesDay(Instant instant) {
        return instant.atZone(KST).toLocalDate();
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
