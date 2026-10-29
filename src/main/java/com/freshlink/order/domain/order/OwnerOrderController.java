package com.freshlink.order.domain.order;

import com.freshlink.common.error.BusinessException;
import com.freshlink.order.common.error.OrderErrorCode;
import com.freshlink.order.common.web.AuthHeaders;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 점주 API. 점포는 X-Store-Id로만 고른다 (경로·쿼리로 점포를 받지 않는다) */
@RestController
@RequestMapping("/owner/orders")
public class OwnerOrderController {

    private final OrderService orderService;

    public OwnerOrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /** OR-04 점주 신규 주문 목록 */
    @GetMapping
    public List<OwnerOrderResponse> list(
            @RequestHeader(name = AuthHeaders.USER_ROLE, required = false) String role,
            @RequestHeader(name = AuthHeaders.STORE_ID, required = false) String storeIdHeader) {
        if (!AuthHeaders.hasRole(role, AuthHeaders.ROLE_OWNER)) {
            throw new BusinessException(OrderErrorCode.FORBIDDEN);
        }
        Long storeId = parseStoreId(storeIdHeader);
        return orderService.listPaidForStoreWithPayment(storeId);
    }

    /** A5 점주 픽업 확인 (기한 이내 결제 완료 주문만) */
    @PostMapping("/{orderId}/pickup")
    public PickupResponse pickUp(
            @PathVariable Long orderId,
            @RequestHeader(name = AuthHeaders.USER_ROLE, required = false) String role,
            @RequestHeader(name = AuthHeaders.STORE_ID, required = false) String storeIdHeader) {
        if (!AuthHeaders.hasRole(role, AuthHeaders.ROLE_OWNER)) {
            throw new BusinessException(OrderErrorCode.FORBIDDEN);
        }
        return PickupResponse.from(orderService.pickUp(parseStoreId(storeIdHeader), orderId));
    }

    /** 없거나 비었거나 양의 정수가 아니면 연결된 점포가 없는 것으로 본다 */
    private static Long parseStoreId(String header) {
        try {
            long id = Long.parseLong(header == null ? "" : header.trim());
            if (id > 0) {
                return id;
            }
        } catch (NumberFormatException ignored) {
            // 아래에서 403
        }
        throw new BusinessException(OrderErrorCode.STORE_NOT_LINKED);
    }
}
