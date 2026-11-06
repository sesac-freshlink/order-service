package com.freshlink.order.domain.pickup;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 픽업 QR 표 저장소 (Redis/Valkey, 추가 명세 A16). DB 표는 두지 않고 5분 뒤 저절로 사라진다.
 * <ul>
 *   <li>{@code pickup:pass:{표}} 고객 ID (TTL 5분)</li>
 *   <li>{@code pickup:code:{6자리}} 표 (TTL 5분). 카메라가 안 될 때 점주가 손으로 넣는 코드</li>
 *   <li>{@code pickup:customer:{고객 ID}} "표:코드". 새 QR을 만들면 예전 표·코드를 지운다</li>
 *   <li>{@code pickup:codefail:{점포 ID}} 코드가 틀린 횟수 (TTL 1분)</li>
 * </ul>
 */
@Component
public class PickupPassStore {

    static final Duration TTL = Duration.ofMinutes(5);
    static final Duration FAIL_WINDOW = Duration.ofMinutes(1);
    /** 1분 안에 이만큼 틀리면 그 점포는 잠시 코드를 못 넣는다 */
    static final int MAX_CODE_FAILS = 10;
    private static final String TOKEN_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final int TOKEN_LENGTH = 32;

    private final StringRedisTemplate redis;
    private final SecureRandom random = new SecureRandom();

    public PickupPassStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 고객의 새 표·코드를 만든다. 예전 표·코드는 지운다 */
    public Pass issue(String customerId) {
        String previous = redis.opsForValue().get(customerKey(customerId));
        if (previous != null) {
            String[] parts = previous.split(":", 2);
            redis.delete(passKey(parts[0]));
            if (parts.length == 2) {
                redis.delete(codeKey(parts[1]));
            }
        }
        String token = randomToken();
        redis.opsForValue().set(passKey(token), customerId, TTL);
        String code = null;
        for (int i = 0; i < 10 && code == null; i++) {
            String candidate = String.format("%06d", random.nextInt(1_000_000));
            if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(codeKey(candidate), token, TTL))) {
                code = candidate;
            }
        }
        if (code == null) {
            throw new IllegalStateException("픽업 코드를 만들지 못했다");
        }
        redis.opsForValue().set(customerKey(customerId), token + ":" + code, TTL);
        return new Pass(token, code);
    }

    /** 표로 고객 ID */
    public Optional<String> customerOf(String token) {
        return Optional.ofNullable(redis.opsForValue().get(passKey(token)));
    }

    /** 6자리 코드로 표 */
    public Optional<String> tokenOfCode(String code) {
        return Optional.ofNullable(redis.opsForValue().get(codeKey(code)));
    }

    /** 이 점포가 1분 안에 코드를 너무 많이 틀렸는지 */
    public boolean codeLocked(Long storeId) {
        String count = redis.opsForValue().get(failKey(storeId));
        return count != null && Integer.parseInt(count) >= MAX_CODE_FAILS;
    }

    /** 코드가 틀린 횟수를 1 늘린다. 처음이면 1분 뒤 저절로 지워진다 */
    public void recordCodeFail(Long storeId) {
        Long count = redis.opsForValue().increment(failKey(storeId));
        if (count != null && count == 1) {
            redis.expire(failKey(storeId), FAIL_WINDOW);
        }
    }

    private String randomToken() {
        StringBuilder sb = new StringBuilder(TOKEN_LENGTH);
        for (int i = 0; i < TOKEN_LENGTH; i++) {
            sb.append(TOKEN_CHARS.charAt(random.nextInt(TOKEN_CHARS.length())));
        }
        return sb.toString();
    }

    static String passKey(String token) {
        return "pickup:pass:" + token;
    }

    static String codeKey(String code) {
        return "pickup:code:" + code;
    }

    static String customerKey(String customerId) {
        return "pickup:customer:" + customerId;
    }

    static String failKey(Long storeId) {
        return "pickup:codefail:" + storeId;
    }

    /** 표(QR 안에 넣는 값)와 6자리 코드 */
    public record Pass(String token, String code) {
    }
}
