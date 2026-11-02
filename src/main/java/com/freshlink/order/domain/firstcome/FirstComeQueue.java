package com.freshlink.order.domain.firstcome;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.stereotype.Component;

/**
 * 선착순 대기열 Redis(Valkey) 저장소. 상품마다 키 묶음(k = storeId:productId)을 둔다.
 * <ul>
 *   <li>{@code fc:seq:k} 일련번호(INCR). 줄 순서 점수로 쓴다. 시계 값은 같은 점수가 생겨 쓰지 않는다 (오픈런 문서 6장)</li>
 *   <li>{@code fc:queue:k} 줄 (정렬 집합, 값 = userId, 점수 = 일련번호)</li>
 *   <li>{@code fc:seen:k} 마지막 순번 조회 시각 (정렬 집합, 점수 = epoch ms). 30초 끊기면 줄에서 뺀다</li>
 *   <li>{@code fc:ticket:k:userId} 입장권 (값 = 만료 epoch ms, TTL 5분)</li>
 *   <li>{@code fc:tickets:k} 살아 있는 입장권 수를 세는 정렬 집합 (점수 = 그 자리가 비는 epoch ms)</li>
 *   <li>{@code fc:active} 스케줄러가 돌 상품 목록 (집합, 값 = k)</li>
 *   <li>{@code fc:bought:k} 입장권으로 주문한 사람 (해시, userId → orderId, 7일). 1인 1개를 이 목록으로 본다</li>
 * </ul>
 * 입장 처리(admit)는 ShedLock을 잡은 스케줄러 한 곳에서만 부른다
 */
@Component
public class FirstComeQueue {

    static final String ACTIVE = "fc:active";
    /**
     * 입장권으로 산 사람 목록을 두는 기간. dev는 금요일 밤 클러스터와 같이 비워지지만, 계속 켜 두는 환경에서도
     * 다음 주에 같은 상품을 다시 선착순으로 열면 다시 살 수 있게 한다 (1인 1개는 선착순 판매 한 번 안의 제한)
     */
    static final Duration BUYERS_TTL = Duration.ofDays(7);

    private final StringRedisTemplate redis;

    public FirstComeQueue(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 줄 서기. 이미 서 있으면 원래 자리 그대로 둔다(ZADD NX). 스케줄러 목록에 상품을 올린다 */
    public void join(QueueKey key, String userId, Instant now) {
        Double existing = redis.opsForZSet().score(queueKey(key), userId);
        if (existing == null) {
            Long seq = redis.opsForValue().increment(seqKey(key));
            redis.opsForZSet().addIfAbsent(queueKey(key), userId, seq == null ? 0 : seq);
        }
        touch(key, userId, now);
    }

    /**
     * 순번 조회를 기록한다 (줄에 있을 때만 의미가 있다). 스케줄러 목록에도 다시 올린다:
     * admit이 "줄이 비었다"고 본 뒤 목록에서 내리기 직전에 누가 줄을 서면 그 상품이 목록에서 빠질 수 있는데,
     * 다음 순번 조회(2~3초 뒤)가 다시 올려 줄 선 사람이 남겨지지 않는다 (SADD라 여러 번 해도 같다)
     */
    public void touch(QueueKey key, String userId, Instant now) {
        redis.opsForZSet().add(seenKey(key), userId, now.toEpochMilli());
        redis.opsForSet().add(ACTIVE, key.value());
    }

    /** 0부터 센 내 자리. 줄에 없으면 null */
    public Long rank(QueueKey key, String userId) {
        return redis.opsForZSet().rank(queueKey(key), userId);
    }

    public long waiting(QueueKey key) {
        Long size = redis.opsForZSet().zCard(queueKey(key));
        return size == null ? 0 : size;
    }

    /** 자리를 차지하고 있는 입장권 수 (주문을 만든 뒤 RESERVE 반영 전까지도 센다) */
    public long activeTickets(QueueKey key, Instant now) {
        Long count = redis.opsForZSet().count(ticketsKey(key), now.toEpochMilli() + 1, Double.POSITIVE_INFINITY);
        return count == null ? 0 : count;
    }

    /** 내 입장권의 만료 시각. 없으면 empty */
    public Optional<Instant> ticket(QueueKey key, String userId) {
        String value = redis.opsForValue().get(ticketKey(key, userId));
        return value == null ? Optional.empty() : Optional.of(Instant.ofEpochMilli(Long.parseLong(value)));
    }

    /** 그만 기다리기: 줄에서만 뺀다. 이미 받은 입장권은 시간이 지나야 사라진다 (오픈런 문서 6장) */
    public void leave(QueueKey key, String userId) {
        redis.opsForZSet().remove(queueKey(key), userId);
        redis.opsForZSet().remove(seenKey(key), userId);
    }

    /**
     * 주문 만들기 직전에 입장권을 쓴다(GETDEL이라 같은 입장권으로 두 번 주문할 수 없다).
     * 자리는 reserveGrace 동안 더 세어 두어, inventory가 RESERVE를 반영하기 전에 같은 자리를 다른 사람에게 주지 않는다
     */
    public Optional<Instant> takeTicket(QueueKey key, String userId, Instant now, Duration reserveGrace) {
        String value = redis.opsForValue().getAndDelete(ticketKey(key, userId));
        if (value == null) {
            return Optional.empty();
        }
        Instant expiresAt = Instant.ofEpochMilli(Long.parseLong(value));
        if (!expiresAt.isAfter(now)) {
            return Optional.empty();
        }
        redis.opsForZSet().add(ticketsKey(key), userId, now.plus(reserveGrace).toEpochMilli());
        return Optional.of(expiresAt);
    }

    /** 주문을 만들지 못했으면 입장권을 남은 시간만큼 되돌린다 */
    public void restoreTicket(QueueKey key, String userId, Instant expiresAt, Instant now) {
        Duration left = Duration.between(now, expiresAt);
        if (left.isNegative() || left.isZero()) {
            redis.opsForZSet().remove(ticketsKey(key), userId);
            return;
        }
        redis.opsForValue().set(ticketKey(key, userId), String.valueOf(expiresAt.toEpochMilli()), left);
        redis.opsForZSet().add(ticketsKey(key), userId, expiresAt.toEpochMilli());
    }

    /** 입장권으로 주문을 만든 사람을 적는다 (1인 1개) */
    public void addBuyer(QueueKey key, String userId, Long orderId) {
        redis.opsForHash().put(buyersKey(key), userId, String.valueOf(orderId));
        redis.expire(buyersKey(key), BUYERS_TTL);
    }

    /** 이 사람이 입장권으로 만든 주문 번호. 없으면 null */
    public Long buyerOrderId(QueueKey key, String userId) {
        Object value = redis.opsForHash().get(buyersKey(key), userId);
        return value == null ? null : Long.valueOf(value.toString());
    }

    /** 그 주문이 실패·취소·환불되면 목록에서 빼서 다시 살 수 있게 한다 */
    public void removeBuyer(QueueKey key, String userId) {
        redis.opsForHash().delete(buyersKey(key), userId);
    }

    public Set<String> activeKeys() {
        Set<String> keys = redis.opsForSet().members(ACTIVE);
        return keys == null ? Set.of() : keys;
    }

    /**
     * 입장 처리 한 번 (스케줄러). 끝난 입장권·순번 조회가 끊긴 사람을 정리하고,
     * 남은 수량에서 살아 있는 입장권 수를 뺀 만큼 앞사람부터 입장권을 준다. 줄과 입장권이 모두 비면 목록에서 내린다.
     * remaining이 null(inventory를 알 수 없음)이면 정리만 한다
     *
     * @return 이번에 입장권을 받은 userId
     */
    public List<String> admit(QueueKey key, Integer remaining, Instant now, Duration ticketTtl, Duration idleTimeout) {
        long nowMs = now.toEpochMilli();
        redis.opsForZSet().removeRangeByScore(ticketsKey(key), Double.NEGATIVE_INFINITY, nowMs);
        Set<String> idle = redis.opsForZSet().rangeByScore(seenKey(key), Double.NEGATIVE_INFINITY,
                nowMs - idleTimeout.toMillis());
        if (idle != null && !idle.isEmpty()) {
            String[] users = idle.toArray(String[]::new);
            redis.opsForZSet().remove(queueKey(key), (Object[]) users);
            redis.opsForZSet().remove(seenKey(key), (Object[]) users);
        }
        List<String> admitted = new ArrayList<>();
        if (remaining != null) {
            long slots = remaining - activeTickets(key, now);
            if (slots > 0) {
                Set<TypedTuple<String>> front = redis.opsForZSet().popMin(queueKey(key), slots);
                Instant expiresAt = now.plus(ticketTtl);
                for (TypedTuple<String> entry : front == null ? Set.<TypedTuple<String>>of() : front) {
                    String userId = entry.getValue();
                    redis.opsForValue().set(ticketKey(key, userId), String.valueOf(expiresAt.toEpochMilli()), ticketTtl);
                    redis.opsForZSet().add(ticketsKey(key), userId, expiresAt.toEpochMilli());
                    redis.opsForZSet().remove(seenKey(key), userId);
                    admitted.add(userId);
                }
            }
        }
        if (waiting(key) == 0 && activeTickets(key, now) == 0) {
            redis.opsForSet().remove(ACTIVE, key.value());
        }
        return admitted;
    }

    /** 선착순이 꺼진 상품: 목록에서 내린다 (줄은 화면이 NOT_FIRST_COME을 받고 주문 화면으로 간다) */
    public void deactivate(QueueKey key) {
        redis.opsForSet().remove(ACTIVE, key.value());
    }

    private static String seqKey(QueueKey key) {
        return "fc:seq:" + key.value();
    }

    private static String queueKey(QueueKey key) {
        return "fc:queue:" + key.value();
    }

    private static String seenKey(QueueKey key) {
        return "fc:seen:" + key.value();
    }

    private static String ticketsKey(QueueKey key) {
        return "fc:tickets:" + key.value();
    }

    private static String buyersKey(QueueKey key) {
        return "fc:bought:" + key.value();
    }

    private static String ticketKey(QueueKey key, String userId) {
        return "fc:ticket:" + key.value() + ":" + userId;
    }
}
