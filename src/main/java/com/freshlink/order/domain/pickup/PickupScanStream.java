package com.freshlink.order.domain.pickup;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 점주 컴퓨터 화면의 픽업 QR 알림 SSE (추가 명세 A16). 점주 핸드폰이 고객 QR을 찍으면 같은 점포의 열린 화면에
 * {@code pickup-scan} 이벤트로 표를 보낸다. 20초마다 빈 이벤트(heartbeat).
 *
 * <p>매출 SSE(SalesStream)와 같이 연결은 이 파드 메모리에만 있다. order 파드는 1개로 운영한다(10/30 결정).
 * 파드를 늘리면 Redis Pub/Sub 같은 방법을 먼저 정한다.
 */
@Component
public class PickupScanStream {

    static final String EVENT = "pickup-scan";
    static final long TIMEOUT_MS = 30 * 60_000L;

    private final Map<Long, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();

    /** 점포의 연결을 하나 더한다. 응답 머리를 바로 보내려고 빈 이벤트를 한 번 보낸다 */
    public SseEmitter subscribe(Long storeId) {
        SseEmitter emitter = newEmitter();
        // 집합 만들기와 더하기를 한 번에 한다. 따로 하면 그 사이 remove 가 빈 집합을 맵에서 지워 이 연결이 버려질 수 있다
        emitters.compute(storeId, (k, set) -> {
            Set<SseEmitter> target = set == null ? ConcurrentHashMap.newKeySet() : set;
            target.add(emitter);
            return target;
        });
        emitter.onCompletion(() -> remove(storeId, emitter));
        emitter.onTimeout(() -> remove(storeId, emitter));
        emitter.onError(e -> remove(storeId, emitter));
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException | IllegalStateException e) {
            remove(storeId, emitter);
        }
        return emitter;
    }

    /** 점포의 열린 화면에 표를 보낸다. 보낸 화면 수를 돌려준다 */
    public int notifyScan(Long storeId, String token) {
        Set<SseEmitter> targets = emitters.get(storeId);
        if (targets == null) {
            return 0;
        }
        int sent = 0;
        for (SseEmitter emitter : targets) {
            try {
                emitter.send(SseEmitter.event().name(EVENT).data(new ScanEvent(token), MediaType.APPLICATION_JSON));
                sent++;
            } catch (IOException | IllegalStateException e) {
                remove(storeId, emitter);
            }
        }
        return sent;
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

    private void remove(Long storeId, SseEmitter emitter) {
        emitters.computeIfPresent(storeId, (k, targets) -> {
            targets.remove(emitter);
            return targets.isEmpty() ? null : targets;
        });
    }

    /** pickup-scan 이벤트 본문 */
    public record ScanEvent(String token) {
    }
}
