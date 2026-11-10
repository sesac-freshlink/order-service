package com.freshlink.order.domain.pickup;

import com.freshlink.common.error.BusinessException;
import com.freshlink.order.common.error.OrderErrorCode;
import com.freshlink.order.common.web.AuthHeaders;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 픽업 QR API (추가 명세 A16, OR-07~OR-11). 고객은 /orders 아래, 점주는 /owner/orders 아래라
 * gateway 길(/api/orders/** CUSTOMER, /api/owner/orders/** OWNER)을 그대로 쓴다. 점주 점포는 X-Store-Id로만 고른다
 */
@RestController
public class PickupPassController {

    private static final int USER_ID_MAX_LENGTH = 36;

    private final PickupPassService service;
    private final PickupScanStream stream;

    public PickupPassController(PickupPassService service, PickupScanStream stream) {
        this.service = service;
        this.stream = stream;
    }

    /** OR-07 고객 픽업 QR 만들기 (예전 QR은 지운다) */
    @PostMapping("/orders/pickup-pass")
    public PickupPassResponse issue(
            @RequestHeader(name = AuthHeaders.USER_ID, required = false) String userId,
            @RequestHeader(name = AuthHeaders.USER_ROLE, required = false) String role) {
        if (!AuthHeaders.hasRole(role, AuthHeaders.ROLE_CUSTOMER)
                || userId == null || userId.isBlank() || userId.length() > USER_ID_MAX_LENGTH) {
            throw new BusinessException(OrderErrorCode.FORBIDDEN);
        }
        return service.issue(userId);
    }

    /** OR-08 점주 핸드폰 스캔: 표를 확인하고 이 점포의 열린 컴퓨터 화면에 알린다 */
    @PostMapping("/owner/orders/pickup-scan")
    public PickupScanResponse scan(
            @RequestHeader(name = AuthHeaders.USER_ROLE, required = false) String role,
            @RequestHeader(name = AuthHeaders.STORE_ID, required = false) String storeIdHeader,
            @Valid @RequestBody ScanRequest request) {
        return service.scan(requireOwnerStore(role, storeIdHeader), request.token());
    }

    /**
     * OR-09 점주 컴퓨터 픽업 QR 알림 SSE. 이벤트 pickup-scan, data {token}. 20초마다 빈 이벤트.
     * 점주가 아니거나 점포가 없으면 본문 없이 403 (매출 SSE와 같은 이유: EventSource는 JSON 오류를 못 받는다)
     */
    @GetMapping(path = "/owner/orders/pickup-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(
            @RequestHeader(name = AuthHeaders.USER_ROLE, required = false) String role,
            @RequestHeader(name = AuthHeaders.STORE_ID, required = false) String storeIdHeader) {
        Long storeId = AuthHeaders.hasRole(role, AuthHeaders.ROLE_OWNER) ? storeIdOrNull(storeIdHeader) : null;
        if (storeId == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(stream.subscribe(storeId));
    }

    /** OR-10 표 또는 6자리 코드로 그 고객의 이 점포 미픽업 주문 */
    @GetMapping("/owner/orders/pickup-pass/{key}")
    public PickupPassOrdersResponse orders(
            @PathVariable String key,
            @RequestHeader(name = AuthHeaders.USER_ROLE, required = false) String role,
            @RequestHeader(name = AuthHeaders.STORE_ID, required = false) String storeIdHeader) {
        return service.orders(requireOwnerStore(role, storeIdHeader), key);
    }

    /** OR-11 고른 주문 일괄 픽업 승인 (1~20건, 건마다 결과) */
    @PostMapping("/owner/orders/pickup")
    public PickupApproveResponse approve(
            @RequestHeader(name = AuthHeaders.USER_ROLE, required = false) String role,
            @RequestHeader(name = AuthHeaders.STORE_ID, required = false) String storeIdHeader,
            @Valid @RequestBody PickupApproveRequest request) {
        return service.approve(requireOwnerStore(role, storeIdHeader), request.token(), request.orderIds());
    }

    private static Long requireOwnerStore(String role, String storeIdHeader) {
        if (!AuthHeaders.hasRole(role, AuthHeaders.ROLE_OWNER)) {
            throw new BusinessException(OrderErrorCode.FORBIDDEN);
        }
        Long storeId = storeIdOrNull(storeIdHeader);
        if (storeId == null) {
            throw new BusinessException(OrderErrorCode.STORE_NOT_LINKED);
        }
        return storeId;
    }

    private static Long storeIdOrNull(String header) {
        try {
            long id = Long.parseLong(header == null ? "" : header.trim());
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 점주 핸드폰이 QR에서 읽은 표 */
    public record ScanRequest(@jakarta.validation.constraints.NotBlank(message = "필수입니다") String token) {
    }
}
