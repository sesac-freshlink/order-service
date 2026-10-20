package com.freshlink.order.domain.order;

import com.freshlink.common.error.BusinessException;
import com.freshlink.order.common.error.OrderErrorCode;
import com.freshlink.order.common.web.AuthHeaders;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private static final String UUID_REGEX =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";
    private static final int USER_ID_MAX_LENGTH = 36;

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /** OR-01 주문 생성: 새 주문 201, 같은 키 재요청 200 */
    @PostMapping
    public ResponseEntity<CreateOrderResponse> create(
            @RequestHeader(name = AuthHeaders.IDEMPOTENCY_KEY, required = false)
            @NotNull @Pattern(regexp = UUID_REGEX) String idempotencyKey,
            @RequestHeader(name = AuthHeaders.USER_ID, required = false) String userId,
            @RequestHeader(name = AuthHeaders.USER_ROLE, required = false) String role,
            @Valid @RequestBody CreateOrderRequest request) {
        if (!AuthHeaders.ROLE_CUSTOMER.equals(role)
                || userId == null || userId.isBlank() || userId.length() > USER_ID_MAX_LENGTH) {
            throw new BusinessException(OrderErrorCode.FORBIDDEN);
        }
        CreateOrderResult result = orderService.create(userId, idempotencyKey.toLowerCase(Locale.ROOT), request);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(CreateOrderResponse.from(result.order()));
    }
}
