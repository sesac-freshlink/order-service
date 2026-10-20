package com.freshlink.order.domain.payment.toss;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 토스페이먼츠 결제 승인 호출. 인증은 Basic(시크릿 키 + ":"), 같은 승인 시도에는 같은 Idempotency-Key를 쓴다.
 * 예외를 던지지 않고 결과를 분류해 돌려준다. 타임아웃·연결 끊김·응답 해석 실패는 결과 불명이다
 */
public class TossPaymentsClient {

    private static final Logger log = LoggerFactory.getLogger(TossPaymentsClient.class);
    private static final String DONE = "DONE";

    private final RestClient restClient;
    private final String secretKey;

    /** builder에는 타임아웃이 걸린 요청 팩토리가 붙어 있어야 한다 (TossConfig). 테스트는 가짜 서버를 붙인다 */
    public TossPaymentsClient(RestClient.Builder builder, TossProperties properties) {
        this.restClient = builder.baseUrl(properties.baseUrl()).build();
        this.secretKey = properties.secretKey();
    }

    public boolean isConfigured() {
        return secretKey != null && !secretKey.isBlank();
    }

    public TossConfirmResult confirm(String paymentKey, String orderNo, long amount, String idempotencyKey) {
        try {
            return restClient.post()
                    .uri("/v1/payments/confirm")
                    .header(HttpHeaders.AUTHORIZATION, basicAuth())
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("paymentKey", paymentKey, "orderId", orderNo, "amount", amount))
                    .exchange((request, response) -> {
                        if (response.getStatusCode().is2xxSuccessful()) {
                            TossPayment body = response.bodyTo(TossPayment.class);
                            if (body != null && DONE.equals(body.status())) {
                                return TossConfirmResult.approved(body.status(), body.method(), parse(body.approvedAt()));
                            }
                            String status = body == null ? null : body.status();
                            log.warn("토스 승인 응답이 성공인데 DONE이 아니다 orderNo={} status={}", orderNo, status);
                            return TossConfirmResult.error(TossOutcome.UNKNOWN, null, "status=" + status);
                        }
                        TossError error = readError(response);
                        String code = error == null ? null : error.code();
                        String message = error == null ? null : error.message();
                        return TossConfirmResult.error(TossErrorClassifier.classify(code), code, message);
                    });
        } catch (RestClientException e) {
            // 타임아웃·연결 끊김·응답 해석 실패. 결제가 됐는지 모르므로 실패로 보지 않는다
            log.warn("토스 승인 호출 결과를 알 수 없다 orderNo={} cause={}", orderNo, e.getClass().getSimpleName());
            return TossConfirmResult.error(TossOutcome.UNKNOWN, null, e.getClass().getSimpleName());
        }
    }

    private String basicAuth() {
        String raw = (secretKey == null ? "" : secretKey) + ":";
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static TossError readError(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        try {
            return response.bodyTo(TossError.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static OffsetDateTime parse(String approvedAt) {
        return approvedAt == null ? null : OffsetDateTime.parse(approvedAt);
    }

    /** 토스 Payment 객체 중 쓰는 필드만 */
    record TossPayment(String status, String method, String approvedAt) {
    }

    /** 토스 오류 응답 {"code": ..., "message": ...} */
    record TossError(String code, String message) {
    }
}
