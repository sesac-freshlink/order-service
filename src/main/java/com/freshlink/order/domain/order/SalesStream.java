package com.freshlink.order.domain.order;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 실시간 매출 SSE. 점주 화면이 연결하면 그 점포의 오늘(KST) 합계를 한 번 보내고,
 * 결제가 완료될 때마다(커밋 뒤) 다시 보낸다. 끊기지 않게 20초마다 빈 이벤트(heartbeat)를 보낸다.
 *
 * <p>연결은 이 파드 메모리에만 있다. order 파드는 1개로 운영한다(gitops, 10/30 결정).
 * 파드를 늘리면 다른 파드의 결제 완료는 전달되지 않으므로 Redis Pub/Sub 같은 방법을 먼저 정한다.
 */
@Component
public class SalesStream {

    /** 이벤트 이름. 화면은 이 이름으로 받는다 */
    static final String EVENT = "sales";
    /** 연결 유지 시간. 지나면 끊고, 화면(EventSource)이 다시 붙어 오늘 합계를 새로 받는다 */
    static final long TIMEOUT_MS = 30 * 60_000L;

    private static final Logger log = LoggerFactory.getLogger(SalesStream.class);

    private final SalesService salesService;
    private final Clock clock;
    private final Map<Long, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SalesStream(SalesService salesService, Clock clock) {
        this.salesService = salesService;
        this.clock = clock;
    }

    /**
     * 점포의 연결을 하나 더하고 지금 오늘 합계를 바로 보낸다.
     * 합계를 읽는 사이의 결제 완료도 받도록 연결을 먼저 넣는다. 읽기가 실패하면 Spring이 이 연결을 받지 못해
     * onCompletion 등이 불리지 않으므로 여기서 빼고 오류를 그대로 던진다.
     */
    public SseEmitter subscribe(Long storeId) {
        SseEmitter emitter = newEmitter();
        emitters.computeIfAbsent(storeId, k -> ConcurrentHashMap.newKeySet()).add(emitter);
        emitter.onCompletion(() -> remove(storeId, emitter));
        emitter.onTimeout(() -> remove(storeId, emitter));
        emitter.onError(e -> remove(storeId, emitter));
        try {
            send(storeId, emitter, salesService.today(storeId, LocalDate.now(clock)));
        } catch (RuntimeException e) {
            remove(storeId, emitter);
            throw e;
        }
        return emitter;
    }

    /** 결제 완료가 커밋된 뒤 그 점포 연결에 오늘 합계를 보낸다. 실패해도 결제 처리에는 영향이 없다 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSalesChanged(SalesChangedEvent event) {
        Set<SseEmitter> targets = emitters.get(event.storeId());
        if (targets == null || targets.isEmpty()) {
            return;
        }
        try {
            TodaySales today = salesService.today(event.storeId(), LocalDate.now(clock));
            for (SseEmitter emitter : targets) {
                send(event.storeId(), emitter, today);
            }
        } catch (RuntimeException e) {
            log.warn("매출 SSE 전송 실패 storeId={}", event.storeId(), e);
        }
    }

    /** 20초마다 빈 이벤트. CloudFront(30초)·ALB(60초)가 쉬는 연결을 끊지 않게 한다 */
    @Scheduled(fixedRateString = "${app.sales-stream.heartbeat-ms:20000}")
    public void heartbeat() {
        emitters.forEach((storeId, targets) -> {
            for (SseEmitter emitter : targets) {
                try {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                } catch (IOException | IllegalStateException e) {
                    remove(storeId, emitter);
                }
            }
        });
    }

    /** 테스트용: 점포의 지금 연결 수 */
    int connections(Long storeId) {
        Set<SseEmitter> targets = emitters.get(storeId);
        return targets == null ? 0 : targets.size();
    }

    /** 테스트에서 보낸 이벤트를 잡으려고 바꿔 끼운다 */
    SseEmitter newEmitter() {
        return new SseEmitter(TIMEOUT_MS);
    }

    private void send(Long storeId, SseEmitter emitter, TodaySales today) {
        try {
            emitter.send(SseEmitter.event().name(EVENT).data(today, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            remove(storeId, emitter);
        }
    }

    private void remove(Long storeId, SseEmitter emitter) {
        emitters.computeIfPresent(storeId, (k, targets) -> {
            targets.remove(emitter);
            return targets.isEmpty() ? null : targets;
        });
    }
}
