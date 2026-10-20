package com.freshlink.order.domain.scheduler;

import com.freshlink.order.domain.order.OrderExpiryService;
import java.time.Clock;
import java.time.Duration;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 1분마다 결제 대기 만료를 돌린다. 파드 2개 중 ShedLock 잠금(order-expiry)을 잡은 한 곳만 돈다.
 * app.expiry.enabled=false면 빈을 만들지 않는다 (테스트)
 */
@Component
@ConditionalOnProperty(prefix = "app.expiry", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OrderExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderExpiryScheduler.class);
    private static final String LOCK_NAME = "order-expiry";
    private static final Duration LOCK_AT_MOST_FOR = Duration.ofMinutes(5);
    private static final Duration LOCK_AT_LEAST_FOR = Duration.ofSeconds(30);

    private final OrderExpiryService expiryService;
    private final LockingTaskExecutor lockingTaskExecutor;
    private final Clock clock;

    public OrderExpiryScheduler(OrderExpiryService expiryService, LockProvider lockProvider, Clock clock) {
        this.expiryService = expiryService;
        this.lockingTaskExecutor = new DefaultLockingTaskExecutor(lockProvider);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.expiry.interval-ms:60000}", initialDelayString = "${app.expiry.initial-delay-ms:30000}")
    public void run() {
        LockConfiguration lock = new LockConfiguration(clock.instant(), LOCK_NAME, LOCK_AT_MOST_FOR, LOCK_AT_LEAST_FOR);
        try {
            lockingTaskExecutor.executeWithLock((Runnable) expiryService::expireDue, lock);
        } catch (RuntimeException e) {
            // 스케줄러 스레드가 죽지 않게 삼키고 다음 차례에 다시 돈다
            log.error("만료 스케줄러 실행 실패", e);
        }
    }
}
