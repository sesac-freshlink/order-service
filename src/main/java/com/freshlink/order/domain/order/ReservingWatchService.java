package com.freshlink.order.domain.order;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A12 재고 확인 결과 미수신 감지 (추가 명세 A12). 기준 시간보다 오래 RESERVING에 머문 주문을 찾아 경고 로그를 한 번 남긴다.
 * 감지만 한다: 주문을 취소하거나 재고 해제(RELEASE)를 보내지 않는다. 개발자가 DLQ·inventory 상태와 대조한 뒤
 * 필요하면 OP-02(멈춘 주문 수동 종료)로 닫는다.
 * 기준 시간·주기는 inventory(이태우님)와 정하기 전의 제안값이라 설정으로 뺐다 (app.reserving-watch.threshold, interval-ms)
 */
@Service
public class ReservingWatchService {

    private static final Logger log = LoggerFactory.getLogger(ReservingWatchService.class);
    static final int BATCH_SIZE = 100;

    private final OrderRepository orderRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final Duration threshold;

    public ReservingWatchService(OrderRepository orderRepository, TransactionTemplate transactionTemplate, Clock clock,
                                 @Value("${app.reserving-watch.threshold:5m}") Duration threshold) {
        this.orderRepository = orderRepository;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.threshold = threshold;
    }

    /** 새로 감지한 건수를 돌려준다 */
    public int detect() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Long> ids = orderRepository.findStuckReservingIds(OrderStatus.RESERVING, now.minus(threshold),
                PageRequest.of(0, BATCH_SIZE));
        int alerted = 0;
        for (Long id : ids) {
            try {
                if (Boolean.TRUE.equals(transactionTemplate.execute(s -> alertOne(id, now)))) {
                    alerted++;
                }
            } catch (RuntimeException e) {
                log.error("재고 확인 지연 감지 실패 orderId={}", id, e);
            }
        }
        return alerted;
    }

    /** 잠근 뒤 다시 확인한다. 그사이 RESERVED·RESERVE_FAILED가 와서 상태가 바뀌었으면 건너뛴다 */
    private boolean alertOne(Long orderId, OffsetDateTime now) {
        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.RESERVING || order.getReservingAlertedAt() != null) {
            return false;
        }
        order.markReservingAlerted(now);
        long waitedMinutes = Duration.between(order.getCreatedAt(), now).toMinutes();
        log.warn("재고 확인 결과를 {}분째 못 받음 (개발자 확인: inventory-commands·inventory-results DLQ와 inventory 처리 상태, "
                        + "필요하면 OP-02로 종료) orderId={} storeId={} productId={} quantity={} createdAt={}",
                waitedMinutes, orderId, order.getStoreId(), order.getProductId(), order.getQuantity(),
                order.getCreatedAt());
        return true;
    }
}
