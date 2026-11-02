package com.freshlink.order.domain.firstcome;

import com.freshlink.order.domain.order.OrderRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 남은 수량 캐시 (Redis·DB 없이). 점포·상품 번호는 요청에서 오므로 캐시가 끝없이 늘지 않아야 한다 */
class FirstComeServiceTest {

    private final InventoryFirstComeClient inventory = mock(InventoryFirstComeClient.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-02T01:00:00Z"), ZoneId.of("Asia/Seoul"));
    private final FirstComeProperties properties = new FirstComeProperties("http://inventory-service:8080",
            Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofMinutes(5), Duration.ofSeconds(30),
            Duration.ofSeconds(2), Duration.ofSeconds(15));
    private final FirstComeService service = new FirstComeService(mock(FirstComeQueue.class), inventory,
            mock(OrderRepository.class), properties, clock);

    @Test
    void 캐시는_상품_수_상한을_넘지_않는다() {
        when(inventory.lookup(anyLong(), anyLong())).thenReturn(FirstComeLookup.notFirstCome());
        for (long productId = 1; productId <= FirstComeService.MAX_CACHED_LOOKUPS + 5; productId++) {
            service.lookup(new QueueKey(1L, productId));
        }
        assertThat(service.cachedLookups()).isLessThanOrEqualTo(FirstComeService.MAX_CACHED_LOOKUPS);
    }

    @Test
    void 시간_안에는_inventory를_다시_부르지_않고_알_수_없는_결과는_남기지_않는다() {
        when(inventory.lookup(1L, 10L)).thenReturn(FirstComeLookup.firstCome(5, 3));
        service.lookup(new QueueKey(1L, 10L));
        service.lookup(new QueueKey(1L, 10L));
        verify(inventory, times(1)).lookup(1L, 10L);

        when(inventory.lookup(1L, 11L)).thenReturn(FirstComeLookup.unknown());
        service.lookup(new QueueKey(1L, 11L));
        service.lookup(new QueueKey(1L, 11L));
        verify(inventory, times(2)).lookup(1L, 11L);
        assertThat(service.cachedLookups()).isEqualTo(1);
    }
}
