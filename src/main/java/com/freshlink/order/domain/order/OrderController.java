package com.freshlink.order.domain.order;

import com.freshlink.common.error.BusinessException;
import com.freshlink.order.common.error.OrderErrorCode;
import com.freshlink.order.common.web.AuthHeaders;
import com.freshlink.order.domain.firstcome.FirstComeOrderGate;
import com.freshlink.order.domain.payment.PaymentConfirmRequest;
import com.freshlink.order.domain.payment.PaymentConfirmResponse;
import com.freshlink.order.domain.payment.PaymentService;
import com.freshlink.order.domain.refund.CancelRequest;
import com.freshlink.order.domain.refund.CancelResponse;
import com.freshlink.order.domain.refund.RefundService;
import jakarta.validation.Valid;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final int USER_ID_MAX_LENGTH = 36;

    private final OrderService orderService;
    private final PaymentService paymentService;
    private final RefundService refundService;
    private final FirstComeOrderGate firstComeOrderGate;

    public OrderController(OrderService orderService, PaymentService paymentService, RefundService refundService,
                           FirstComeOrderGate firstComeOrderGate) {
        this.orderService = orderService;
        this.paymentService = paymentService;
        this.refundService = refundService;
        this.firstComeOrderGate = firstComeOrderGate;
    }

    /**
     * OR-01 주문 생성: 새 주문 201, 같은 키 재요청 200.
     * 선착순 상품이면 입장권이 있어야 하고 1개만 살 수 있다(FirstComeOrderGate). 주문을 만들지 못하면 입장권을 되돌린다
     */
    @PostMapping
    public ResponseEntity<CreateOrderResponse> create(
            @RequestHeader(name = AuthHeaders.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestHeader(name = AuthHeaders.USER_ID, required = false) String userId,
            @RequestHeader(name = AuthHeaders.USER_ROLE, required = false) String role,
            @Valid @RequestBody CreateOrderRequest request) {
        requireIdempotencyKey(idempotencyKey);
        requireCustomer(userId, role);
        String key = idempotencyKey.toLowerCase(Locale.ROOT);
        FirstComeOrderGate.Admission admission = firstComeOrderGate.admit(userId, key, request);
        CreateOrderResult result;
        try {
            result = orderService.create(userId, key, request);
        } catch (RuntimeException e) {
            firstComeOrderGate.release(admission);
            throw e;
        }
        firstComeOrderGate.complete(admission, result.order().getId());
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(CreateOrderResponse.from(result.order()));
    }

    /**
     * A3 고객 주문 내역: tab = PICKUP_WAITING·PICKED_UP·CANCELED·ALL(기본), page(0부터)·size(10·20·50, 기본 20).
     * from·to(KST 날짜 yyyy-MM-dd, 주문일시 기준, 둘 다 포함)는 선택. 탭과 같이 쓴다.
     * q(11/11)는 상품명 일부(앞뒤 공백 빼고 50자까지, 대소문자 구분 없음). 비우면 조건 없음, 50자 넘으면 400
     */
    @GetMapping
    public PageResponse<CustomerOrderSummaryResponse> list(
            @RequestParam(required = false, defaultValue = "ALL") String tab,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String q,
            @RequestHeader(name = AuthHeaders.USER_ID, required = false) String userId,
            @RequestHeader(name = AuthHeaders.USER_ROLE, required = false) String role) {
        requireCustomer(userId, role);
        return orderService.listForCustomer(userId, OrderPageRequest.tab(CustomerOrderTab.class, tab),
                OrderPeriod.of(from, to), OrderKeyword.likePattern(q), OrderPageRequest.of(page, size));
    }

    /** OR-02 주문 조회: 본인 주문만 */
    @GetMapping("/{orderId}")
    public OrderResponse get(
            @PathVariable Long orderId,
            @RequestHeader(name = AuthHeaders.USER_ID, required = false) String userId,
            @RequestHeader(name = AuthHeaders.USER_ROLE, required = false) String role) {
        requireCustomer(userId, role);
        return orderService.getDetail(userId, orderId);
    }

    /** OR-03 결제 승인: 토스 결제창 성공 뒤 화면이 부른다. 결과는 status로 알린다 */
    @PostMapping("/{orderId}/payment/confirm")
    public PaymentConfirmResponse confirmPayment(
            @PathVariable Long orderId,
            @RequestHeader(name = AuthHeaders.USER_ID, required = false) String userId,
            @RequestHeader(name = AuthHeaders.USER_ROLE, required = false) String role,
            @Valid @RequestBody PaymentConfirmRequest request) {
        requireCustomer(userId, role);
        return paymentService.confirm(userId, orderId, request);
    }

    /**
     * A7 결제 전 취소: 결제 대기 화면의 "주문 취소". 사유는 받지 않고 환불금액은 0원이다.
     * 승인 중·결과 확인 중이거나 결제가 끝난 주문은 409 (결제 후 취소는 A8)
     */
    @PostMapping("/{orderId}/payment/cancel")
    public OrderResponse cancelBeforePayment(
            @PathVariable Long orderId,
            @RequestHeader(name = AuthHeaders.USER_ID, required = false) String userId,
            @RequestHeader(name = AuthHeaders.USER_ROLE, required = false) String role) {
        requireCustomer(userId, role);
        orderService.cancelBeforePayment(userId, orderId);
        return orderService.getDetail(userId, orderId);
    }

    /**
     * 고객 픽업 신청 (11/10): "오늘 받아 갈게요". 본인 결제 완료 주문만, 기한을 오늘 23:59(KST)로 당기고 점주에게 알린다.
     * 이미 신청했거나 준비 완료면 그대로 돌려준다. 신청할 수 없는 상태면 409 ORDER_NOT_PICKUP_REQUESTABLE, 기한이 지났으면 409
     */
    @PostMapping("/{orderId}/pickup-request")
    public OrderResponse requestPickup(
            @PathVariable Long orderId,
            @RequestHeader(name = AuthHeaders.USER_ID, required = false) String userId,
            @RequestHeader(name = AuthHeaders.USER_ROLE, required = false) String role) {
        requireCustomer(userId, role);
        orderService.requestPickup(userId, orderId);
        return orderService.getDetail(userId, orderId);
    }

    /**
     * A8 고객 결제 후 취소: 본인 PAID·환불 NONE·픽업 기한 이내 주문을 전체 취소·전액 환불한다(점주 승인 없음).
     * 환불이 실패(FAILED)한 주문은 같은 주소로 다시 신청한다(사유는 새로 받는다). 결과는 refundStatus로 알린다
     */
    @PostMapping("/{orderId}/cancel")
    public CancelResponse cancel(
            @PathVariable Long orderId,
            @RequestHeader(name = AuthHeaders.USER_ID, required = false) String userId,
            @RequestHeader(name = AuthHeaders.USER_ROLE, required = false) String role,
            @RequestBody(required = false) CancelRequest request) {
        requireCustomer(userId, role);
        return refundService.cancelByCustomer(userId, orderId, request);
    }

    /**
     * 주문 요청 키: 없거나 UUID가 아니면 400 VALIDATION_ERROR.
     * 헤더에 @NotNull·@Pattern을 붙이면 본문 @Valid 오류까지 메서드 검증 예외로 바뀌어 칸 이름이 사라지므로 여기서 직접 본다
     */
    private static void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || !UUID_PATTERN.matcher(idempotencyKey).matches()) {
            throw new BusinessException(OrderErrorCode.VALIDATION_ERROR);
        }
    }

    /** 고객 API 공통: 역할이 CUSTOMER이고 X-User-Id가 있어야 한다. 아니면 403 FORBIDDEN */
    private static void requireCustomer(String userId, String role) {
        if (!AuthHeaders.hasRole(role, AuthHeaders.ROLE_CUSTOMER)
                || userId == null || userId.isBlank() || userId.length() > USER_ID_MAX_LENGTH) {
            throw new BusinessException(OrderErrorCode.FORBIDDEN);
        }
    }
}
