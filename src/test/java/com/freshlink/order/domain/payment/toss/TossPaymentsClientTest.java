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
        assertThat(r.receiptUrl()).isNull();
    }

    @Test
    void 승인_성공_응답의_receipt_url을_영수증_주소로_읽는다() {
        server.expect(requestTo(BASE + "/v1/payments/confirm"))
                .andRespond(withSuccess("{\"status\":\"DONE\",\"method\":\"카드\",\"approvedAt\":\"2026-10-20T15:35:10+09:00\","
                        + "\"receipt\":{\"url\":\"https://dashboard.tosspayments.com/receipt/redirection?transactionId=tviva_1\"}}",
                        MediaType.APPLICATION_JSON));

        TossConfirmResult r = confirm();

        assertThat(r.outcome()).isEqualTo(TossOutcome.APPROVED);
        assertThat(r.receiptUrl())
                .isEqualTo("https://dashboard.tosspayments.com/receipt/redirection?transactionId=tviva_1");
    }

    @Test
    void receipt가_null이어도_승인_성공이고_영수증_주소만_없다() {
        server.expect(requestTo(BASE + "/v1/payments/confirm"))
                .andRespond(withSuccess("{\"status\":\"DONE\",\"method\":\"카드\",\"receipt\":null}",
                        MediaType.APPLICATION_JSON));

        TossConfirmResult r = confirm();

        assertThat(r.outcome()).isEqualTo(TossOutcome.APPROVED);
        assertThat(r.receiptUrl()).isNull();
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

    @Test
    void 승인_시각_형식이_낯설어도_승인_성공으로_본다() {
        server.expect(requestTo(BASE + "/v1/payments/confirm"))
                .andRespond(withSuccess("{\"status\":\"DONE\",\"method\":\"카드\",\"approvedAt\":\"2026/10/20 15:35\"}",
                        MediaType.APPLICATION_JSON));

        TossConfirmResult r = confirm();

        assertThat(r.outcome()).isEqualTo(TossOutcome.APPROVED);
        assertThat(r.approvedAt()).isNull();
    }

    // ---------------------------------------------------------------- 결제 조회 (OP-01)

    @Test
    void 결제_조회는_GET_paymentKey로_부르고_상태를_읽는다() {
        String basic = "Basic " + Base64.getEncoder().encodeToString((SECRET + ":").getBytes(StandardCharsets.UTF_8));
        server.expect(requestTo(BASE + "/v1/payments/pk_1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", basic))
                .andRespond(withSuccess("{\"status\":\"DONE\",\"method\":\"카드\","
                        + "\"approvedAt\":\"2026-10-20T15:35:10+09:00\",\"orderId\":\"FL-20261020-000123\","
                        + "\"totalAmount\":3400,\"receipt\":{\"url\":\"https://receipt.test/1\"}}",
                        MediaType.APPLICATION_JSON));

        TossLookupResult r = client.lookup("pk_1");

        server.verify();
        assertThat(r.found()).isTrue();
        assertThat(r.status()).isEqualTo("DONE");
        assertThat(r.method()).isEqualTo("카드");
        assertThat(r.approvedAt()).isEqualTo(OffsetDateTime.parse("2026-10-20T15:35:10+09:00"));
        assertThat(r.orderId()).isEqualTo("FL-20261020-000123");
        assertThat(r.totalAmount()).isEqualTo(3400L);
        assertThat(r.receiptUrl()).isEqualTo("https://receipt.test/1");
    }

    @Test
    void 결제_조회_오류나_타임아웃은_찾지_못한_것으로_본다() {
        server.expect(requestTo(BASE + "/v1/payments/pk_1"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body(error("NOT_FOUND_PAYMENT")));
        TossLookupResult notFound = client.lookup("pk_1");
        assertThat(notFound.found()).isFalse();
        assertThat(notFound.errorCode()).isEqualTo("NOT_FOUND_PAYMENT");

        server.reset();
        server.expect(requestTo(BASE + "/v1/payments/pk_1"))
                .andRespond(withException(new SocketTimeoutException("read timed out")));
        assertThat(client.lookup("pk_1").found()).isFalse();
    }

    // ---------------------------------------------------------------- A9 결제 취소

    @Test
    void 취소는_POST_cancel에_사유와_멱등키를_보내고_CANCELED면_취소_시각을_읽는다() {
        server.expect(requestTo(BASE + "/v1/payments/pk_1/cancel"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", KEY))
                .andExpect(jsonPath("$.cancelReason").value("단순 변심"))
                .andExpect(jsonPath("$.cancelAmount").doesNotExist())
                .andRespond(withSuccess("{\"status\":\"CANCELED\",\"cancels\":[{\"cancelAmount\":3400,"
                        + "\"canceledAt\":\"2026-10-25T15:00:03+09:00\",\"cancelStatus\":\"DONE\"}]}",
                        MediaType.APPLICATION_JSON));

        TossCancelResult r = client.cancel("pk_1", "단순 변심", KEY);

        server.verify();
        assertThat(r.outcome()).isEqualTo(TossCancelOutcome.CANCELED);
        assertThat(r.canceledAt()).isEqualTo(OffsetDateTime.parse("2026-10-25T15:00:03+09:00"));
    }

    @Test
    void 취소_오류는_취소_목록으로_분류하고_타임아웃은_결과_불명() {
        server.expect(requestTo(BASE + "/v1/payments/pk_1/cancel"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body(error("NOT_CANCELABLE_PAYMENT")));
        server.expect(requestTo(BASE + "/v1/payments/pk_1/cancel"))
                .andRespond(withException(new SocketTimeoutException("read timed out")));

        TossCancelResult rejected = client.cancel("pk_1", "단순 변심", KEY);
        TossCancelResult timeout = client.cancel("pk_1", "단순 변심", KEY);

        assertThat(rejected.outcome()).isEqualTo(TossCancelOutcome.DEFINITE_FAILURE);
        assertThat(rejected.errorCode()).isEqualTo("NOT_CANCELABLE_PAYMENT");
        assertThat(timeout.outcome()).isEqualTo(TossCancelOutcome.UNKNOWN);
    }

    @Test
    void 취소_확인_조회는_CANCELED면_취소_DONE이면_아직_취소_안_됨_오류는_결과_불명() {
        server.expect(requestTo(BASE + "/v1/payments/pk_1")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"status\":\"CANCELED\",\"cancels\":[{\"canceledAt\":\"2026-10-25T15:00:03+09:00\"}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v1/payments/pk_1"))
                .andRespond(withSuccess("{\"status\":\"DONE\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v1/payments/pk_1"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON)
                        .body(error("COMMON_ERROR")));

        assertThat(client.lookupCancel("pk_1").outcome()).isEqualTo(TossCancelOutcome.CANCELED);
        assertThat(client.lookupCancel("pk_1").outcome()).isEqualTo(TossCancelOutcome.NOT_CANCELED);
        assertThat(client.lookupCancel("pk_1").outcome()).isEqualTo(TossCancelOutcome.UNKNOWN);
    }
}
