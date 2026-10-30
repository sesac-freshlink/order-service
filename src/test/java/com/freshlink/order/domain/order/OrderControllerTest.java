package com.freshlink.order.domain.order;

import com.freshlink.common.error.BusinessException;
import com.freshlink.order.common.error.GlobalExceptionHandler;
import com.freshlink.order.common.error.OrderErrorCode;
import com.freshlink.order.domain.payment.Payment;
import com.freshlink.order.domain.payment.PaymentService;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({OrderController.class, OwnerOrderController.class})
@Import(GlobalExceptionHandler.class)
class OrderControllerTest {

    private static final String KEY = "0b5f2c3e-8d1a-4f6b-9c7d-1234567890ab";
    private static final String USER = "11111111-1111-1111-1111-111111111111";
    private static final String BODY =
            "{\"storeId\":1,\"storeName\":\"프레시마트 강남점\",\"productId\":10,\"quantity\":2}";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    OrderService orderService;
    @MockitoBean
    PaymentService paymentService;
    @MockitoBean
    SalesService salesService;
    @MockitoBean
    SalesStream salesStream;

    private MockHttpServletRequestBuilder valid(String body) {
        return post("/orders").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Idempotency-Key", KEY).header("X-User-Id", USER).header("X-User-Role", "CUSTOMER");
    }

    /** 헤더 하나만 바꾼 요청 (header()는 값을 덧붙이므로 새로 만든다) */
    private MockHttpServletRequestBuilder with(String key, String userId, String role) {
        MockHttpServletRequestBuilder b = post("/orders").contentType(MediaType.APPLICATION_JSON).content(BODY)
                .header("Idempotency-Key", key);
        if (userId != null) b.header("X-User-Id", userId);
        if (role != null) b.header("X-User-Role", role);
        return b;
    }

    private Order order(long id) {
        Order o = Order.create(USER, KEY, 1L, "프레시마트 강남점", 10L, 2);
        ReflectionTestUtils.setField(o, "id", id);
        o.assignOrderNo("FL-20261020-000123");
        return o;
    }

    private String body(String storeId, String storeName, String productId, String quantity) {
        return "{\"storeId\":" + storeId + ",\"storeName\":" + storeName
                + ",\"productId\":" + productId + ",\"quantity\":" + quantity + "}";
    }

    @Test
    void 새_주문은_201과_주문번호_상태를_돌려준다() throws Exception {
        when(orderService.create(eq(USER), eq(KEY), any())).thenReturn(new CreateOrderResult(order(123L), true));

        mockMvc.perform(valid(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value(123))
                .andExpect(jsonPath("$.orderNo").value("FL-20261020-000123"))
                .andExpect(jsonPath("$.status").value("RESERVING"));
    }

    @Test
    void 같은_키_재요청은_200() throws Exception {
        when(orderService.create(eq(USER), eq(KEY), any())).thenReturn(new CreateOrderResult(order(123L), false));

        mockMvc.perform(valid(BODY)).andExpect(status().isOk());
    }

    @Test
    void 대문자_키는_소문자로_바꿔_서비스에_넘긴다() throws Exception {
        when(orderService.create(any(), any(), any())).thenReturn(new CreateOrderResult(order(1L), true));

        mockMvc.perform(with(KEY.toUpperCase(), USER, "CUSTOMER"))
                .andExpect(status().isCreated());

        verify(orderService).create(eq(USER), eq(KEY), any());
    }

    @Test
    void 서비스가_던진_409는_그대로_나간다() throws Exception {
        when(orderService.create(any(), any(), any()))
                .thenThrow(new BusinessException(OrderErrorCode.IDEMPOTENCY_KEY_REUSED));

        mockMvc.perform(valid(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void 본문이_틀리면_400_VALIDATION_ERROR() throws Exception {
        String[] bad = {
                body("1", "\"점포\"", "10", "0"),
                body("1", "\"점포\"", "10", "6"),
                body("1", "\"점포\"", "10", "null"),
                body("1", "\"\"", "10", "2"),
                body("1", "\"" + "가".repeat(31) + "\"", "10", "2"),
                body("null", "\"점포\"", "10", "2"),
                body("1", "\"점포\"", "null", "2"),
        };
        for (String b : bad) {
            mockMvc.perform(valid(b))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        verifyNoInteractions(orderService);
    }

    @Test
    void 본문_오류_message에_칸_이름이_나온다() throws Exception {
        // 본문 오류는 칸 이름, 키 오류는 idempotencyKey가 message 앞에 나온다
        mockMvc.perform(valid(body("1", "\"점포\"", "10", "6")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(startsWith("quantity: ")));

        mockMvc.perform(with("abc", USER, "CUSTOMER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(startsWith("idempotencyKey: ")));
    }

    @Test
    void 키가_없거나_UUID가_아니면_400_VALIDATION_ERROR() throws Exception {
        mockMvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("X-User-Id", USER).header("X-User-Role", "CUSTOMER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(with("abc", USER, "CUSTOMER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(orderService);
    }

    @Test
    void 고객이_아니면_403_FORBIDDEN() throws Exception {
        mockMvc.perform(with(KEY, USER, "OWNER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Idempotency-Key", KEY).header("X-User-Id", USER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verifyNoInteractions(orderService);
    }

    @Test
    void 사용자_ID가_없거나_비었거나_36자를_넘으면_403_FORBIDDEN() throws Exception {
        mockMvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Idempotency-Key", KEY).header("X-User-Role", "CUSTOMER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(with(KEY, " ", "CUSTOMER"))
                .andExpect(status().isForbidden());
        mockMvc.perform(with(KEY, "a".repeat(37), "CUSTOMER"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(orderService);
    }

    // ---------------------------------------------------------------- OR-02 주문 조회

    private MockHttpServletRequestBuilder getOrder(String id, String userId, String role) {
        MockHttpServletRequestBuilder b = get("/orders/" + id);
        if (userId != null) b.header("X-User-Id", userId);
        if (role != null) b.header("X-User-Role", role);
        return b;
    }

    @Test
    void 주문_조회는_200과_18개_필드를_돌려주고_시각은_KST다() throws Exception {
        Order o = order(123L);
        // DB에서 UTC로 읽혀도 응답은 +09:00이어야 한다
        ReflectionTestUtils.setField(o, "createdAt", OffsetDateTime.parse("2026-10-20T06:31:55Z"));
        o.reserve("매운 컵라면 115g", 1700L, OffsetDateTime.parse("2026-10-20T06:32:00Z"));
        when(orderService.getDetail(USER, 123L))
                .thenReturn(OrderResponse.from(o, null, OffsetDateTime.parse("2026-10-20T06:35:00Z")));

        mockMvc.perform(getOrder("123", USER, "CUSTOMER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(123))
                .andExpect(jsonPath("$.orderNo").value("FL-20261020-000123"))
                .andExpect(jsonPath("$.status").value("PAYMENT_WAITING"))
                .andExpect(jsonPath("$.failureReason").isEmpty())
                .andExpect(jsonPath("$.storeId").value(1))
                .andExpect(jsonPath("$.storeName").value("프레시마트 강남점"))
                .andExpect(jsonPath("$.productId").value(10))
                .andExpect(jsonPath("$.productName").value("매운 컵라면 115g"))
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.unitPrice").value(1700))
                .andExpect(jsonPath("$.amount").value(3400))
                .andExpect(jsonPath("$.paymentExpiresAt").value("2026-10-20T15:42:00+09:00"))
                .andExpect(jsonPath("$.paidAt").isEmpty())
                .andExpect(jsonPath("$.createdAt").value("2026-10-20T15:31:55+09:00"))
                .andExpect(jsonPath("$.payment").isEmpty())
                .andExpect(jsonPath("$.serverTime").value("2026-10-20T15:35:00+09:00"));
    }

    @Test
    void 예약_중인_주문은_상품명_단가_금액_마감이_null() throws Exception {
        when(orderService.getDetail(USER, 123L)).thenReturn(response(order(123L)));

        mockMvc.perform(getOrder("123", USER, "CUSTOMER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESERVING"))
                .andExpect(jsonPath("$.productName").isEmpty())
                .andExpect(jsonPath("$.unitPrice").isEmpty())
                .andExpect(jsonPath("$.amount").isEmpty())
                .andExpect(jsonPath("$.paymentExpiresAt").isEmpty())
                .andExpect(jsonPath("$.paidAt").isEmpty());
    }

    @Test
    void 주문_조회도_고객이_아니거나_사용자가_없으면_403_FORBIDDEN() throws Exception {
        mockMvc.perform(getOrder("123", USER, "OWNER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(getOrder("123", null, "CUSTOMER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verifyNoInteractions(orderService);
    }

    @Test
    void 없는_주문은_404_남의_주문은_403_ORDER_ACCESS_DENIED() throws Exception {
        when(orderService.getDetail(USER, 1L)).thenThrow(new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
        when(orderService.getDetail(USER, 2L)).thenThrow(new BusinessException(OrderErrorCode.ORDER_ACCESS_DENIED));

        mockMvc.perform(getOrder("1", USER, "CUSTOMER"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        mockMvc.perform(getOrder("2", USER, "CUSTOMER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORDER_ACCESS_DENIED"))
                .andExpect(jsonPath("$.message").value("본인 주문만 볼 수 있습니다"));
    }

    @Test
    void 주문_ID가_숫자가_아니면_400() throws Exception {
        mockMvc.perform(getOrder("abc", USER, "CUSTOMER")).andExpect(status().isBadRequest());
        verifyNoInteractions(orderService);
    }

    // ---------------------------------------------------------------- OR-04 점주 신규 주문 목록

    private MockHttpServletRequestBuilder ownerOrders(String role, String storeId) {
        MockHttpServletRequestBuilder b = get("/owner/orders").header("X-User-Id", USER);
        if (role != null) b.header("X-User-Role", role);
        if (storeId != null) b.header("X-Store-Id", storeId);
        return b;
    }

    private Order paid(long id, String paidAt) {
        Order o = order(id);
        o.reserve("매운 컵라면 115g", 1700L, OffsetDateTime.parse("2026-10-20T06:00:00Z"));
        ReflectionTestUtils.setField(o, "status", OrderStatus.PAID);
        ReflectionTestUtils.setField(o, "paidAt", OffsetDateTime.parse(paidAt));
        return o;
    }

    private OrderResponse response(Order o) {
        return OrderResponse.from(o, null, OffsetDateTime.parse("2026-10-20T06:35:00Z"));
    }

    @Test
    void 점주_목록은_200과_10개_필드를_돌려주고_결제_시각은_KST다() throws Exception {
        Payment payment = Payment.start(123L, "pk_1", "33333333-3333-3333-3333-333333333333", 3400L);
        payment.approve("DONE", "카드", OffsetDateTime.parse("2026-10-20T06:35:10Z"), "https://receipt.test/1");
        when(orderService.listPaidForStoreWithPayment(7L))
                .thenReturn(List.of(OwnerOrderResponse.from(paid(123L, "2026-10-20T06:35:10Z"), payment)));

        mockMvc.perform(ownerOrders("OWNER", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].orderId").value(123))
                .andExpect(jsonPath("$[0].orderNo").value("FL-20261020-000123"))
                .andExpect(jsonPath("$[0].status").value("PAID"))
                .andExpect(jsonPath("$[0].pickedUpAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[0].productName").value("매운 컵라면 115g"))
                .andExpect(jsonPath("$[0].quantity").value(2))
                .andExpect(jsonPath("$[0].amount").value(3400))
                .andExpect(jsonPath("$[0].paidAt").value("2026-10-20T15:35:10+09:00"))
                .andExpect(jsonPath("$[0].payment.method").value("카드"))
                .andExpect(jsonPath("$[0].payment.approvedAt").value("2026-10-20T15:35:10+09:00"))
                .andExpect(jsonPath("$[0].payment.status").value("DONE"))
                .andExpect(jsonPath("$[0].payment.receiptUrl").value("https://receipt.test/1"))
                .andExpect(jsonPath("$[0].customerId").doesNotExist());
    }

    @Test
    void 점주가_아니면_403_FORBIDDEN() throws Exception {
        mockMvc.perform(ownerOrders("CUSTOMER", "7"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(ownerOrders(null, "7"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verifyNoInteractions(orderService);
    }

    @Test
    void 점포가_없거나_비었거나_숫자가_아니면_403_STORE_NOT_LINKED() throws Exception {
        for (String storeId : new String[] {null, "", " ", "abc", "0", "-1"}) {
            mockMvc.perform(ownerOrders("OWNER", storeId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("STORE_NOT_LINKED"));
        }
        verifyNoInteractions(orderService);
    }

    @Test
    void 그룹이_여럿이면_쉼표로_오고_그중_하나만_맞으면_된다() throws Exception {
        when(orderService.listPaidForStoreWithPayment(7L)).thenReturn(List.of());
        when(orderService.getDetail(USER, 123L)).thenReturn(response(order(123L)));

        mockMvc.perform(ownerOrders("CUSTOMER, OWNER", "7")).andExpect(status().isOk());
        mockMvc.perform(getOrder("123", USER, "OWNER,CUSTOMER")).andExpect(status().isOk());
        mockMvc.perform(getOrder("123", USER, "OWNERX,ADMIN")).andExpect(status().isForbidden());
    }

    @Test
    void 매출_SSE는_점주의_X_Store_Id로_연결한다() throws Exception {
        when(salesStream.subscribe(7L)).thenReturn(new SseEmitter());

        mockMvc.perform(get("/owner/orders/sales/stream").accept(MediaType.TEXT_EVENT_STREAM)
                        .header("X-User-Id", USER).header("X-User-Role", "OWNER").header("X-Store-Id", "7"))
                .andExpect(request().asyncStarted());

        verify(salesStream).subscribe(7L);
    }

    @Test
    void 매출_SSE는_점주가_아니거나_점포가_없으면_본문_없이_403() throws Exception {
        mockMvc.perform(get("/owner/orders/sales/stream").accept(MediaType.TEXT_EVENT_STREAM)
                        .header("X-User-Id", USER).header("X-User-Role", "CUSTOMER").header("X-Store-Id", "7"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(""));
        mockMvc.perform(get("/owner/orders/sales/stream").accept(MediaType.TEXT_EVENT_STREAM)
                        .header("X-User-Id", USER).header("X-User-Role", "OWNER").header("X-Store-Id", "abc"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(""));

        verifyNoInteractions(salesStream);
    }
}
