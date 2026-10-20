package com.freshlink.order.common.error;

import com.freshlink.common.error.BusinessException;
import com.freshlink.common.error.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.stream.Collectors;

/**
 * 모든 오류를 common의 {@link ErrorResponse}(code, message, traceId) 모양으로 바꿔 응답한다.
 * 규칙은 .github 레포 docs/백엔드-공통-규칙.md 6장.
 *
 * <table>
 *   <tr><th>예외</th><th>상태</th><th>code</th></tr>
 *   <tr><td>BusinessException</td><td>enum의 status</td><td>enum 이름</td></tr>
 *   <tr><td>@Valid 실패</td><td>400</td><td>VALIDATION_ERROR (message에 필드별 사유)</td></tr>
 *   <tr><td>Spring이 상태를 아는 예외 (없는 경로, 405 ...)</td><td>그 상태</td><td>NOT_FOUND, METHOD_NOT_ALLOWED ...</td></tr>
 *   <tr><td>그 밖</td><td>500</td><td>INTERNAL_ERROR (원인은 로그에만)</td></tr>
 * </table>
 *
 * 오류 코드를 추가할 땐 {@code OrderErrorCode} enum에 한 줄만 넣는다. 이 클래스는 안 건드린다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e, HttpServletRequest request) {
        log.warn("{} {} -> {} {} ({})", request.getMethod(), request.getRequestURI(), e.status(), e.code(), e.getMessage());
        return ResponseEntity.status(e.status()).body(ErrorResponse.of(e, traceId()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnknown(Exception e, HttpServletRequest request) {
        log.error("{} {} -> 500 INTERNAL_ERROR", request.getMethod(), request.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_ERROR", "서버 오류가 발생했습니다", traceId()));
    }

    /** Spring MVC가 던지는 예외 전부가 여기를 거친다 (@Valid 실패, 없는 경로, 405, 본문 파싱 실패 ...) */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        String code = status != null ? status.name() : "ERROR";
        String message = status != null ? status.getReasonPhrase() : ex.getMessage();

        if (ex instanceof MethodArgumentNotValidException e) {
            code = "VALIDATION_ERROR";
            message = e.getBindingResult().getFieldErrors().stream()
                    .map(f -> f.getField() + ": " + f.getDefaultMessage())
                    .collect(Collectors.joining(", "));
        } else if (ex instanceof HandlerMethodValidationException e) {
            code = "VALIDATION_ERROR";
            message = e.getParameterValidationResults().stream()
                    .flatMap(r -> r.getResolvableErrors().stream()
                            .map(err -> r.getMethodParameter().getParameterName() + ": " + err.getDefaultMessage()))
                    .collect(Collectors.joining(", "));
        }

        String path = request instanceof ServletWebRequest s ? s.getRequest().getMethod() + " " + s.getRequest().getRequestURI() : "";
        log.warn("{} -> {} {} ({})", path, statusCode.value(), code, message);
        return ResponseEntity.status(statusCode).headers(headers).body(new ErrorResponse(code, message, traceId()));
    }

    /** Micrometer Tracing이 MDC에 넣어 둔 traceId. 로그·Zipkin과 같은 값. 추적이 꺼져 있으면 "" */
    private static String traceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }
}
