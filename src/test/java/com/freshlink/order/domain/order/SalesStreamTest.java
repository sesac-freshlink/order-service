package com.freshlink.order.domain.order;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 매출 SSE: 연결 때 오늘 합계, 결제 완료 때 그 점포에만 다시, 20초 heartbeat, 끊긴 연결 정리 */
class SalesStreamTest {

    /** 2026-10-30 00:10 KST (UTC로는 전날 15:10). 오늘은 KST 날짜여야 한다 */
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-29T15:10:00Z"), ZoneId.of("Asia/Seoul"));
    static final LocalDate TODAY = LocalDate.of(2026, 10, 30);

    SalesService salesService;
    List<RecordingEmitter> created;
    SalesStream stream;

    /** 보낸 이벤트를 글자로 모아 두는 emitter. fail이면 보내기에서 IOException */
    static class RecordingEmitter extends SseEmitter {
        final List<String> sent = new ArrayList<>();
        boolean fail;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (fail) {
                throw new IOException("끊김");
            }
            sent.add(builder.build().stream().map(d -> String.valueOf(d.getData())).collect(Collectors.joining()));
        }
    }

    @BeforeEach
    void setUp() {
        salesService = mock(SalesService.class);
        created = new ArrayList<>();
        stream = new SalesStream(salesService, CLOCK) {
            @Override
            SseEmitter newEmitter() {
                RecordingEmitter e = new RecordingEmitter();
                created.add(e);
                return e;
            }
        };
    }

    @Test
    void 연결하면_KST_오늘_합계를_sales_이벤트로_한_번_보낸다() {
        when(salesService.today(7L, TODAY)).thenReturn(new TodaySales("2026-10-30", 3400, 2));

        stream.subscribe(7L);

        assertThat(created).hasSize(1);
        assertThat(created.get(0).sent).hasSize(1);
        assertThat(created.get(0).sent.get(0))
                .contains("event:sales")
                .contains("TodaySales[date=2026-10-30, paidAmount=3400, paidCount=2]");
        assertThat(stream.connections(7L)).isEqualTo(1);
    }

    @Test
    void 결제가_완료되면_그_점포_연결에만_새_합계를_보낸다() {
        when(salesService.today(7L, TODAY)).thenReturn(new TodaySales("2026-10-30", 0, 0),
                new TodaySales("2026-10-30", 1700, 1));
        when(salesService.today(8L, TODAY)).thenReturn(new TodaySales("2026-10-30", 0, 0));
        stream.subscribe(7L);
        stream.subscribe(8L);

        stream.onSalesChanged(new SalesChangedEvent(7L));

        assertThat(created.get(0).sent).hasSize(2);
        assertThat(created.get(0).sent.get(1)).contains("paidAmount=1700, paidCount=1");
        assertThat(created.get(1).sent).hasSize(1);
    }

    @Test
    void 연결할_때_합계_읽기가_실패하면_연결을_남기지_않고_오류를_그대로_던진다() {
        when(salesService.today(7L, TODAY)).thenThrow(new IllegalStateException("DB 오류"));

        assertThatThrownBy(() -> stream.subscribe(7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DB 오류");
        assertThat(stream.connections(7L)).isZero();
    }

    @Test
    void 연결이_없는_점포의_결제_완료는_합계를_다시_읽지_않는다() {
        stream.onSalesChanged(new SalesChangedEvent(9L));

        verifyNoInteractions(salesService);
    }

    @Test
    void heartbeat는_모든_연결에_빈_이벤트를_보내고_끊긴_연결은_뺀다() {
        when(salesService.today(any(), any())).thenReturn(new TodaySales("2026-10-30", 0, 0));
        stream.subscribe(7L);
        stream.subscribe(7L);
        created.get(1).fail = true;

        stream.heartbeat();

        assertThat(created.get(0).sent).hasSize(2);
        assertThat(created.get(0).sent.get(1)).contains(":heartbeat");
        assertThat(stream.connections(7L)).isEqualTo(1);
    }

    @Test
    void 결제_완료_전송이_실패한_연결은_빼고_합계_조회_오류는_밖으로_던지지_않는다() {
        when(salesService.today(7L, TODAY)).thenReturn(new TodaySales("2026-10-30", 0, 0));
        stream.subscribe(7L);
        created.get(0).fail = true;

        stream.onSalesChanged(new SalesChangedEvent(7L));
        assertThat(stream.connections(7L)).isZero();

        stream.subscribe(7L);
        when(salesService.today(7L, TODAY)).thenThrow(new IllegalStateException("DB 오류"));
        stream.onSalesChanged(new SalesChangedEvent(7L));
        verify(salesService, atLeastOnce()).today(7L, TODAY);
    }
}
