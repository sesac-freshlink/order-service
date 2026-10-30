package com.freshlink.order.domain.order;

import com.freshlink.order.domain.payment.ApprovedPayment;
import com.freshlink.order.domain.payment.PaymentRepository;
import com.freshlink.order.domain.payment.PaymentStatus;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 점주 매출 대시보드 API. 점포는 X-Store-Id로만 고르고, 그 점포 주문의 승인 완료(DONE) 결제를 센다.
 * 취소 건수는 결제 전 고객 취소(A7, CANCELED)를 취소 시각 기준 KST 날짜로 센다
 */
@Service
public class SalesService {

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;

    public SalesService(PaymentRepository paymentRepository, OrderRepository orderRepository) {
        this.paymentRepository = paymentRepository;
        this.orderRepository = orderRepository;
    }

    @Transactional(readOnly = true)
    public SalesResponse sales(Long storeId, SalesPeriod period, SalesUnit unit) {
        Map<LocalDate, long[]> buckets = new LinkedHashMap<>();
        for (LocalDate start = unit.startOf(period.from()); !start.isAfter(period.to()); start = unit.next(start)) {
            buckets.put(start, new long[3]); // [결제 금액, 결제 건수, 결제 전 취소 건수]
        }
        for (ApprovedPayment p : paymentRepository.findApprovedForStore(
                storeId, PaymentStatus.DONE, period.start(), period.end())) {
            long[] b = buckets.get(unit.startOf(kstDate(p.approvedAt())));
            b[0] += p.amount();
            b[1]++;
        }
        for (OffsetDateTime closedAt : orderRepository.findClosedAtForStore(
                storeId, OrderStatus.CANCELED, period.start(), period.end())) {
            buckets.get(unit.startOf(kstDate(closedAt)))[2]++;
        }
        List<SalesResponse.Period> periods = new ArrayList<>(buckets.size());
        buckets.forEach((start, b) -> periods.add(
                new SalesResponse.Period(unit.label(start), b[0], 0, b[0], b[1], 0, b[2])));
        return new SalesResponse(period.from().toString(), period.to().toString(),
                unit.name().toLowerCase(Locale.ROOT), periods);
    }

    /** 매출 SSE용: 그 점포의 date(KST) 하루 결제 금액·건수 */
    @Transactional(readOnly = true)
    public TodaySales today(Long storeId, LocalDate date) {
        SalesPeriod day = SalesPeriod.of(date.toString(), date.toString());
        long amount = 0;
        long count = 0;
        for (ApprovedPayment p : paymentRepository.findApprovedForStore(
                storeId, PaymentStatus.DONE, day.start(), day.end())) {
            amount += p.amount();
            count++;
        }
        return new TodaySales(date.toString(), amount, count);
    }

    private static LocalDate kstDate(OffsetDateTime at) {
        return at.atZoneSameInstant(SalesPeriod.KST).toLocalDate();
    }
}
