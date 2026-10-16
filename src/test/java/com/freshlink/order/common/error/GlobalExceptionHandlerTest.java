package com.freshlink.order.common.error;

import com.freshlink.common.error.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 오류 응답이 항상 code, message, traceId 세 키로 나가는지 확인한다. DB 없이 돈다 */
@WebMvcTest(controllers = GlobalExceptionHandlerTest.ErrorTestController.class)
@Import(GlobalExceptionHandlerTest.ErrorTestController.class)   // 테스트 클래스의 중첩 클래스는 슬라이스 스캔에서 빠지므로 직접 등록
class GlobalExceptionHandlerTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void BusinessException은_enum의_status와_이름으로_나간다() throws Exception {
        mockMvc.perform(get("/test-errors/business"))
                .andExpect(status().is(404))
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(OrderErrorCode.ORDER_NOT_FOUND.message()))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void Valid_실패는_400_VALIDATION_ERROR_필드별_사유() throws Exception {
        mockMvc.perform(post("/test-errors/valid").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("name: ")))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void 없는_경로는_404_NOT_FOUND() throws Exception {
        mockMvc.perform(get("/test-errors/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void 허용되지_않은_메서드는_405_METHOD_NOT_ALLOWED() throws Exception {
        mockMvc.perform(post("/test-errors/business"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void 그_밖의_예외는_500_INTERNAL_ERROR_원인은_응답에_없다() throws Exception {
        mockMvc.perform(get("/test-errors/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("boom"))))
                .andExpect(jsonPath("$.traceId").value(""));
    }

    @RestController
    @RequestMapping("/test-errors")
    static class ErrorTestController {

        record Req(@NotBlank String name) {
        }

        @GetMapping("/business")
        String business() {
            throw new BusinessException(OrderErrorCode.ORDER_NOT_FOUND);
        }

        @PostMapping("/valid")
        String valid(@Valid @RequestBody Req req) {
            return "ok";
        }

        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("boom");
        }
    }
}
