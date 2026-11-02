package com.freshlink.order.domain.firstcome;

import com.freshlink.common.error.BusinessException;
import com.freshlink.order.common.error.OrderErrorCode;
import com.freshlink.order.common.web.AuthHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 선착순 대기열 API (오픈런 문서 2장). 고객만. 선착순은 점포마다 켜므로 storeId를 쿼리로 받는다.
 * 브라우저는 /api/orders/queue/...로 부르고 gateway의 /api/orders/** 라우트를 그대로 탄다
 */
@RestController
@RequestMapping("/orders/queue")
public class FirstComeQueueController {

    private static final int USER_ID_MAX_LENGTH = 36;

    private final FirstComeService firstComeService;

    public FirstComeQueueController(FirstComeService firstComeService) {
        this.firstComeService = firstComeService;
    }

    /** 줄 서기. 이미 서 있으면 원래 자리 그대로 */
    @PostMapping("/{productId}")
    public QueueStatusResponse join(
            @PathVariable Long productId,
            @RequestParam Long storeId,
            @RequestHeader(name = AuthHeaders.USER_ID, required = false) String userId,
            @RequestHeader(name = AuthHeaders.USER_ROLE, required = false) String role) {
        requireCustomer(userId, role);
        return firstComeService.join(userId, new QueueKey(storeId, productId));
    }

    /** 내 순번·앞에 남은 사람 수·남은 수량·입장권 (대기 화면이 2~3초마다 부른다) */
    @GetMapping("/{productId}/me")
    public QueueStatusResponse me(
            @PathVariable Long productId,
            @RequestParam Long storeId,
            @RequestHeader(name = AuthHeaders.USER_ID, required = false) String userId,
            @RequestHeader(name = AuthHeaders.USER_ROLE, required = false) String role) {
        requireCustomer(userId, role);
        return firstComeService.me(userId, new QueueKey(storeId, productId));
    }

    /** 그만 기다리기 */
    @DeleteMapping("/{productId}/me")
    public ResponseEntity<Void> leave(
            @PathVariable Long productId,
            @RequestParam Long storeId,
            @RequestHeader(name = AuthHeaders.USER_ID, required = false) String userId,
            @RequestHeader(name = AuthHeaders.USER_ROLE, required = false) String role) {
        requireCustomer(userId, role);
        firstComeService.leave(userId, new QueueKey(storeId, productId));
        return ResponseEntity.noContent().build();
    }

    private static void requireCustomer(String userId, String role) {
        if (!AuthHeaders.hasRole(role, AuthHeaders.ROLE_CUSTOMER)
                || userId == null || userId.isBlank() || userId.length() > USER_ID_MAX_LENGTH) {
            throw new BusinessException(OrderErrorCode.FORBIDDEN);
        }
    }
}
