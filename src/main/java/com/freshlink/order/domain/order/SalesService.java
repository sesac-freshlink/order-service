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

/** 점주 매출 대시보드 API. 점포는 X-Store-Id로만 고르고, 그 점포 주문의 승인 완료(DONE) 결제만 센다 */
@Service
public class SalesService {

    private final PaymentRepository paymentRepository;

    public SalesService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @Transactional(readOnly = true)
    public SalesResponse sales(Long storeId, SalesPeriod period, SalesUnit unit) {
        Map<LocalDate, long[]> buckets = new LinkedHashMap<>();
        for (LocalDate start = unit.startOf(period.from()); !start.isAfter(period.to()); start = unit.next(start)) {
            buckets.put(start, new long[2]); // [결제 금액, 결제 건수]
        }
        for (ApprovedPayment p : paymentRepository.findApprovedForStore(
                storeId, PaymentStatus.DONE, period.start(), period.end())) {
            long[] b = buckets.get(unit.startOf(kstDate(p.approvedAt())));
            b[0] += p.amount();
            b[1]++;
        }
        List<SalesResponse.Period> periods = new ArrayList<>(buckets.size());
        buckets.forEach((start, b) -> periods.add(new SalesResponse.Period(unit.label(start), b[0], 0, b[0], b[1], 0, 0)));
        return new SalesResponse(period.from().toString(), period.to().toString(),
                unit.name().toLowerCase(Locale.ROOT), periods);
    }

    private static LocalDate kstDate(OffsetDateTime at) {
        return at.atZoneSameInstant(SalesPeriod.KST).toLocalDate();
    }
}
