package com.freshlink.order.domain.payment.toss;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 토스 승인 호출의 요청 모양과 응답 해석. 실제 토스에 접속하지 않는다 (가짜 서버) */
class TossPaymentsClientTest {

    private static final String BASE = "https://toss.test";
    private static final String SECRET = "test_sk_dummy";
    private static final String KEY = "0b5f2c3e-8d1a-4f6b-9c7d-1234567890ab";

    MockRestServiceServer server;
    TossPaymentsClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TossPaymentsClient(builder,
                new TossProperties(BASE, SECRET, Duration.ofSeconds(3), Duration.ofSeconds(15)));
    }

    private TossConfirmResult confirm() {
        return client.confirm("pk_1", "FL-20261020-000123", 3400L, KEY);
    }

    private String error(String code) {
        return "{\"code\":\"" + code + "\",\"message\":\"토스 메시지\"}";
    }

    @Test
    void 승인_성공() {
        String basic = "Basic " + Base64.getEncoder().encodeToString((SECRET + ":").getBytes(StandardCharsets.UTF_8));
        server.expect(requestTo(BASE + "/v1/payments/confirm"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", basic))
                .andExpect(header("Idempotency-Key", KEY))
                .andExpect(jsonPath("$.paymentKey").value("pk_1"))
                .andExpect(jsonPath("$.orderId").value("FL-20261020-000123"))
                .andExpect(jsonPath("$.amount").value(3400))
                .andRespond(withSuccess("{\"paymentKey\":\"pk_1\",\"status\":\"DONE\",\"method\":\"카드\","
                        + "\"approvedAt\":\"2026-10-20T15:35:10+09:00\"}", MediaType.APPLICATION_JSON));

        TossConfirmResult r = confirm();

        server.verify();
        assertThat(r.outcome()).isEqualTo(TossOutcome.APPROVED);
        assertThat(r.tossStatus()).isEqualTo("DONE");
        assertThat(r.method()).isEqualTo("카드");
        assertThat(r.approvedAt()).isEqualTo(OffsetDateTime.parse("2026-10-20T15:35:10+09:00"));
    }

    @Test
    void 성공_응답인데_DONE이_아니면_결과_불명() {
        server.expect(requestTo(BASE + "/v1/payments/confirm"))
                .andRespond(withSuccess("{\"status\":\"IN_PROGRESS\"}", MediaType.APPLICATION_JSON));

        assertThat(confirm().outcome()).isEqualTo(TossOutcome.UNKNOWN);
    }

    @Test
    void 오류_응답은_코드로_분류하고_코드와_메시지를_남긴다() {
        server.expect(requestTo(BASE + "/v1/payments/confirm"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body(error("REJECT_CARD_PAYMENT")));

        TossConfirmResult r = confirm();

        assertThat(r.outcome()).isEqualTo(TossOutcome.DEFINITE_FAILURE);
        assertThat(r.errorCode()).isEqualTo("REJECT_CARD_PAYMENT");
        assertThat(r.errorMessage()).isEqualTo("토스 메시지");
    }

    @Test
    void 상태코드가_아니라_오류_코드로_분류한다_400_PROVIDER_ERROR는_결과_불명() {
        server.expect(requestTo(BASE + "/v1/payments/confirm"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body(error("PROVIDER_ERROR")));

        assertThat(confirm().outcome()).isEqualTo(TossOutcome.UNKNOWN);
    }

    @Test
    void 키_오류는_연동_설정_오류() {
        server.expect(requestTo(BASE + "/v1/payments/confirm"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body(error("UNAUTHORIZED_KEY")));

        assertThat(confirm().outcome()).isEqualTo(TossOutcome.CONFIG_ERROR);
    }

    @Test
    void 타임아웃은_결과_불명() {
        server.expect(requestTo(BASE + "/v1/payments/confirm"))
                .andRespond(withException(new SocketTimeoutException("read timed out")));

        TossConfirmResult r = confirm();

        assertThat(r.outcome()).isEqualTo(TossOutcome.UNKNOWN);
        assertThat(r.errorCode()).isNull();
    }

    @Test
    void 해석할_수_없는_오류_본문은_결과_불명() {
        server.expect(requestTo(BASE + "/v1/payments/confirm"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY).contentType(MediaType.TEXT_HTML).body("<html>"));

        assertThat(confirm().outcome()).isEqualTo(TossOutcome.UNKNOWN);
    }

    @Test
    void 시크릿_키가_비면_설정되지_않은_것으로_본다() {
        TossPaymentsClient empty = new TossPaymentsClient(RestClient.builder(),
                new TossProperties(BASE, " ", Duration.ofSeconds(3), Duration.ofSeconds(15)));

        assertThat(empty.isConfigured()).isFalse();
        assertThat(client.isConfigured()).isTrue();
    }
}
