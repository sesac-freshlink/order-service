package com.freshlink.order.domain.order;

import com.freshlink.common.error.BusinessException;
import com.freshlink.order.common.error.OrderErrorCode;
import com.freshlink.order.common.web.AuthHeaders;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 점주 API. 점포는 X-Store-Id로만 고른다 (경로·쿼리로 점포를 받지 않는다) */
@RestController
@RequestMapping("/owner/orders")
public class OwnerOrderController {

    private final OrderService orderService;
    private final SalesService salesService;
    private final SalesStream salesStream;

    public OwnerOrderController(OrderService orderService, SalesService salesService, SalesStream salesStream) {
        this.orderService = orderService;
        this.salesService = salesService;
        this.salesStream = salesStream;
    }

    /** OR-04 점주 신규 주문 목록 (MVP 그대로: 결제 완료 주문 배열, 페이지 없음). tab이 없을 때 */
    @GetMapping(params = "!tab")
    public List<OwnerOrderResponse> list(
            @RequestHeader(name = AuthHeaders.USER_ROLE, required = false) String role,
            @RequestHeader(name = AuthHeaders.STORE_ID, required = false) String storeIdHeader) {
        if (!AuthHeaders.hasRole(role, AuthHeaders.ROLE_OWNER)) {
            throw new BusinessException(OrderErrorCode.FORBIDDEN);
        }
        Long storeId = parseStoreId(storeIdHeader);
        return orderService.listPaidForStoreWithPayment(storeId);
    }

    /**
     * A6 점주 주문 목록 (OR-04 확장): tab = PICKUP_WAITING·PICKED_UP·CANCELED·PICKUP_EXPIRED, page(0부터)·size(10·20·50).
     * 같은 주소에 tab을 붙이면 페이지 응답이 온다. tab이 없으면 위 MVP 응답(배열) 그대로라 예전 화면도 깨지지 않는다.
     * from·to(KST 날짜 yyyy-MM-dd, 주문일시 기준, 둘 다 포함)는 선택이고 tab과 같이 쓴다
     */
    @GetMapping(params = "tab")
    public PageResponse<OwnerOrderResponse> listByTab(
            @RequestParam String tab,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestHeader(name = AuthHeaders.USER_ROLE, required = false) String role,
            @RequestHeader(name = AuthHeaders.STORE_ID, required = false) String storeIdHeader) {
        if (!AuthHeaders.hasRole(role, AuthHeaders.ROLE_OWNER)) {
            throw new BusinessException(OrderErrorCode.FORBIDDEN);
        }
        Long storeId = parseStoreId(storeIdHeader);
        return orderService.listForStore(storeId, OrderPageRequest.tab(OwnerOrderTab.class, tab),
                OrderPeriod.of(from, to), OrderPageRequest.of(page, size));
    }

    /** N-02 점주 픽업 준비 완료 (기한 이내 결제 완료 주문만). 고객에게 픽업 준비 완료 알림이 간다 */
    @PostMapping("/{orderId}/ready")
    public PickupResponse markReady(
            @PathVariable Long orderId,
            @RequestHeader(name = AuthHeaders.USER_ROLE, required = false) String role,
            @RequestHeader(name = AuthHeaders.STORE_ID, required = false) String storeIdHeader) {
        if (!AuthHeaders.hasRole(role, AuthHeaders.ROLE_OWNER)) {
            throw new BusinessException(OrderErrorCode.FORBIDDEN);
        }
        return PickupResponse.from(orderService.markReadyForPickup(parseStoreId(storeIdHeader), orderId));
    }

    /** A5 점주 픽업 확인 (기한 이내 픽업 준비 완료 주문만) */
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

    /**
     * 점주 매출 대시보드: 내 점포의 기간별 결제·환불 금액, 순매출, 결제·환불·취소 건수.
     * from·to는 KST 날짜 yyyy-MM-dd(필수, 둘 다 포함, 최대 366일), unit = day(기본)·week(월요일 시작)·month
     */
    @GetMapping("/sales")
    public SalesResponse sales(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String unit,
            @RequestHeader(name = AuthHeaders.USER_ROLE, required = false) String role,
            @RequestHeader(name = AuthHeaders.STORE_ID, required = false) String storeIdHeader) {
        if (!AuthHeaders.hasRole(role, AuthHeaders.ROLE_OWNER)) {
            throw new BusinessException(OrderErrorCode.FORBIDDEN);
        }
        return salesService.sales(parseStoreId(storeIdHeader), SalesPeriod.of(from, to), SalesUnit.of(unit));
    }

    /**
     * 실시간 매출 SSE: 연결하면 내 점포의 오늘(KST) 합계를 한 번 보내고, 결제가 완료될 때마다 다시 보낸다.
     * 이벤트 이름 sales, data {date, paidAmount, paidCount}. 20초마다 빈 이벤트(heartbeat)
     *
     * <p>점주가 아니거나 점포가 없으면 본문 없이 403만 준다. 브라우저 EventSource는 Accept가 text/event-stream뿐이라
     * 공통 오류 응답(JSON)을 쓸 수 없고(쓰려 하면 500이 된다), 403이면 다시 연결하지 않고 멈춘다.
     */
    @GetMapping(path = "/sales/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> salesStream(
            @RequestHeader(name = AuthHeaders.USER_ROLE, required = false) String role,
            @RequestHeader(name = AuthHeaders.STORE_ID, required = false) String storeIdHeader) {
        Long storeId = AuthHeaders.hasRole(role, AuthHeaders.ROLE_OWNER) ? storeIdOrNull(storeIdHeader) : null;
        if (storeId == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(salesStream.subscribe(storeId));
    }

    /** 없거나 비었거나 양의 정수가 아니면 연결된 점포가 없는 것으로 본다 */
    private static Long parseStoreId(String header) {
        Long id = storeIdOrNull(header);
        if (id == null) {
            throw new BusinessException(OrderErrorCode.STORE_NOT_LINKED);
        }
        return id;
    }

    private static Long storeIdOrNull(String header) {
        try {
            long id = Long.parseLong(header == null ? "" : header.trim());
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
