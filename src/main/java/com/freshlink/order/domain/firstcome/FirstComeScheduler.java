package com.freshlink.order.domain.firstcome;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
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
 * 선착순 입장 처리. 2초마다 줄이 있는 상품마다 남은 수량(inventory)을 새로 묻고, 끝난 입장권·순번 조회가 30초 끊긴 사람을
 * 정리한 뒤 빈자리만큼 앞사람에게 입장권(5분)을 준다. ShedLock 잠금 order-first-come을 잡은 한 곳만 돈다.
 * app.expiry.enabled=false면 다른 스케줄러처럼 빈을 만들지 않는다 (테스트)
 */
@Component
@ConditionalOnProperty(prefix = "app.expiry", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FirstComeScheduler {

    private static final Logger log = LoggerFactory.getLogger(FirstComeScheduler.class);
    private static final String LOCK_NAME = "order-first-come";
    private static final Duration LOCK_AT_MOST_FOR = Duration.ofSeconds(30);
    private static final Duration LOCK_AT_LEAST_FOR = Duration.ofSeconds(1);

    private final FirstComeService firstComeService;
    private final FirstComeQueue queue;
    private final FirstComeProperties properties;
    private final LockingTaskExecutor lockingTaskExecutor;
    private final Clock clock;

    public FirstComeScheduler(FirstComeService firstComeService, FirstComeQueue queue, FirstComeProperties properties,
                              LockProvider lockProvider, Clock clock) {
        this.firstComeService = firstComeService;
        this.queue = queue;
        this.properties = properties;
        this.lockingTaskExecutor = new DefaultLockingTaskExecutor(lockProvider);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.first-come.admit-interval-ms:2000}",
            initialDelayString = "${app.first-come.admit-initial-delay-ms:10000}")
    public void run() {
        LockConfiguration lock = new LockConfiguration(clock.instant(), LOCK_NAME, LOCK_AT_MOST_FOR, LOCK_AT_LEAST_FOR);
        try {
            lockingTaskExecutor.executeWithLock((Runnable) this::admitAll, lock);
        } catch (RuntimeException e) {
            // Redis·DB가 잠깐 안 돼도 스케줄러 스레드가 죽지 않게 삼키고 다음 차례에 다시 돈다
            log.error("선착순 입장 처리 실패", e);
        }
    }

    /** 줄이 있는 상품마다 한 번씩 입장 처리. 한 상품이 실패해도 다른 상품은 계속한다 */
    public void admitAll() {
        for (String value : queue.activeKeys()) {
            QueueKey key = QueueKey.parse(value);
            try {
                admit(key);
            } catch (RuntimeException e) {
                log.warn("선착순 입장 처리 실패 storeId={} productId={} cause={}", key.storeId(), key.productId(),
                        e.getClass().getSimpleName());
            }
        }
    }

    void admit(QueueKey key) {
        FirstComeLookup lookup = firstComeService.refresh(key);
        if (lookup.kind() == FirstComeLookup.Kind.NOT_FIRST_COME) {
            queue.deactivate(key);
            return;
        }
        List<String> admitted = queue.admit(key, lookup.remaining(), clock.instant(), properties.ticketTtl(),
                properties.idleTimeout());
        if (!admitted.isEmpty()) {
            log.info("선착순 입장 storeId={} productId={} count={} remaining={}", key.storeId(), key.productId(),
                    admitted.size(), lookup.remaining());
        }
    }
}
