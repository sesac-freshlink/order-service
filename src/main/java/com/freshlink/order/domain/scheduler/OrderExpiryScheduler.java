package com.freshlink.order.domain.scheduler;

import com.freshlink.order.domain.order.OrderExpiryService;
import com.freshlink.order.domain.order.PickupDeadlineService;
import com.freshlink.order.domain.payment.PaymentAutoSyncService;
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
 * 1분마다 결제 대기 만료와 픽업 기한 경과(A4), 10초마다 결제 결과 자동 조회(A11)를 돌린다.
 * 파드 2개 중 ShedLock 잠금(order-expiry, order-pickup-deadline, order-payment-sync)을 잡은 한 곳만 돈다.
 * 잠금 이름을 나눠 한쪽이 길어져도 다른 쪽을 막지 않는다.
 * app.expiry.enabled=false면 빈을 만들지 않는다 (테스트)
 */
@Component
@ConditionalOnProperty(prefix = "app.expiry", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OrderExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderExpiryScheduler.class);
    private static final String LOCK_NAME = "order-expiry";
    private static final String PICKUP_LOCK_NAME = "order-pickup-deadline";
    private static final String PAYMENT_SYNC_LOCK_NAME = "order-payment-sync";
    /** 자동 조회는 10초마다 돌아 오래 잡지 않는다 (토스 조회 타임아웃 고려) */
    private static final Duration PAYMENT_SYNC_LOCK_AT_MOST_FOR = Duration.ofMinutes(2);
    private static final Duration PAYMENT_SYNC_LOCK_AT_LEAST_FOR = Duration.ofSeconds(5);
    private static final Duration LOCK_AT_MOST_FOR = Duration.ofMinutes(5);
    private static final Duration LOCK_AT_LEAST_FOR = Duration.ofSeconds(30);

    private final OrderExpiryService expiryService;
    private final PickupDeadlineService pickupDeadlineService;
    private final PaymentAutoSyncService paymentAutoSyncService;
    private final LockingTaskExecutor lockingTaskExecutor;
    private final Clock clock;

    public OrderExpiryScheduler(OrderExpiryService expiryService, PickupDeadlineService pickupDeadlineService,
                                PaymentAutoSyncService paymentAutoSyncService, LockProvider lockProvider, Clock clock) {
        this.expiryService = expiryService;
        this.pickupDeadlineService = pickupDeadlineService;
        this.paymentAutoSyncService = paymentAutoSyncService;
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

    @Scheduled(fixedDelayString = "${app.expiry.interval-ms:60000}", initialDelayString = "${app.expiry.initial-delay-ms:30000}")
    public void runPickupDeadline() {
        LockConfiguration lock = new LockConfiguration(clock.instant(), PICKUP_LOCK_NAME, LOCK_AT_MOST_FOR, LOCK_AT_LEAST_FOR);
        try {
            lockingTaskExecutor.executeWithLock((Runnable) pickupDeadlineService::expireDue, lock);
        } catch (RuntimeException e) {
            log.error("픽업 기한 스케줄러 실행 실패", e);
        }
    }

    @Scheduled(fixedDelayString = "${app.payment-sync.interval-ms:10000}",
            initialDelayString = "${app.expiry.initial-delay-ms:30000}")
    public void runPaymentSync() {
        LockConfiguration lock = new LockConfiguration(clock.instant(), PAYMENT_SYNC_LOCK_NAME,
                PAYMENT_SYNC_LOCK_AT_MOST_FOR, PAYMENT_SYNC_LOCK_AT_LEAST_FOR);
        try {
            lockingTaskExecutor.executeWithLock((Runnable) paymentAutoSyncService::syncDue, lock);
        } catch (RuntimeException e) {
            log.error("결제 자동 조회 스케줄러 실행 실패", e);
        }
    }
}
