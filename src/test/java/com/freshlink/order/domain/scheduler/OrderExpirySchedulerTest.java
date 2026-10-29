package com.freshlink.order.domain.scheduler;

import com.freshlink.order.domain.order.OrderExpiryService;
import com.freshlink.order.domain.order.PickupDeadlineService;
import com.freshlink.order.domain.payment.PaymentAutoSyncService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 파드 2개 중 잠금을 잡은 쪽만 만료를 돌린다 */
class OrderExpirySchedulerTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-20T06:50:00Z"), ZoneId.of("Asia/Seoul"));
    private final OrderExpiryService service = mock(OrderExpiryService.class);
    private final PickupDeadlineService pickupService = mock(PickupDeadlineService.class);
    private final PaymentAutoSyncService paymentSyncService = mock(PaymentAutoSyncService.class);
    private final LockProvider lockProvider = mock(LockProvider.class);
    private final OrderExpiryScheduler scheduler =
            new OrderExpiryScheduler(service, pickupService, paymentSyncService, lockProvider, clock);

    @Test
    void 잠금을_잡으면_만료를_돌리고_잠금_이름은_order_expiry() {
        when(lockProvider.lock(any())).thenReturn(Optional.of(mock(SimpleLock.class)));

        scheduler.run();

        verify(service).expireDue();
        ArgumentCaptor<LockConfiguration> lock = ArgumentCaptor.forClass(LockConfiguration.class);
        verify(lockProvider).lock(lock.capture());
        assertThat(lock.getValue().getName()).isEqualTo("order-expiry");
    }

    @Test
    void 다른_파드가_잠금을_잡고_있으면_건너뛴다() {
        when(lockProvider.lock(any())).thenReturn(Optional.empty());

        scheduler.run();

        verify(service, never()).expireDue();
    }

    @Test
    void 픽업_기한은_따로_잠금_order_pickup_deadline으로_돌린다() {
        when(lockProvider.lock(any())).thenReturn(Optional.of(mock(SimpleLock.class)));

        scheduler.runPickupDeadline();

        verify(pickupService).expireDue();
        verify(service, never()).expireDue();
        ArgumentCaptor<LockConfiguration> lock = ArgumentCaptor.forClass(LockConfiguration.class);
        verify(lockProvider).lock(lock.capture());
        assertThat(lock.getValue().getName()).isEqualTo("order-pickup-deadline");
    }

    @Test
    void 픽업_기한_처리_중_예외가_나도_스케줄러_스레드는_죽지_않는다() {
        when(lockProvider.lock(any())).thenReturn(Optional.of(mock(SimpleLock.class)));
        when(pickupService.expireDue()).thenThrow(new IllegalStateException("db down"));

        scheduler.runPickupDeadline();
    }

    @Test
    void 만료_중_예외가_나도_스케줄러_스레드는_죽지_않는다() {
        when(lockProvider.lock(any())).thenReturn(Optional.of(mock(SimpleLock.class)));
        when(service.expireDue()).thenThrow(new IllegalStateException("db down"));

        scheduler.run();
    }

    @Test
    void 결제_자동_조회는_따로_잠금_order_payment_sync로_돌린다() {
        when(lockProvider.lock(any())).thenReturn(Optional.of(mock(SimpleLock.class)));

        scheduler.runPaymentSync();

        verify(paymentSyncService).syncDue();
        verify(service, never()).expireDue();
        ArgumentCaptor<LockConfiguration> lock = ArgumentCaptor.forClass(LockConfiguration.class);
        verify(lockProvider).lock(lock.capture());
        assertThat(lock.getValue().getName()).isEqualTo("order-payment-sync");
    }

    @Test
    void 결제_자동_조회_중_예외가_나도_스케줄러_스레드는_죽지_않는다() {
        when(lockProvider.lock(any())).thenReturn(Optional.of(mock(SimpleLock.class)));
        when(paymentSyncService.syncDue()).thenThrow(new IllegalStateException("toss down"));

        scheduler.runPaymentSync();
    }
}
