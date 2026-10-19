package com.freshlink.order.domain.order;

import com.freshlink.common.error.BusinessException;
import com.freshlink.order.common.error.GlobalExceptionHandler;
import com.freshlink.order.common.error.OrderErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
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
        org.springframework.test.util.ReflectionTestUtils.setField(o, "id", id);
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
}
