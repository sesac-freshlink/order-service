package com.freshlink.order.common.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 상태 전이 시각(결제 대기·마감·종료)은 이 시계로 정한다. 테스트에서 고정 시계로 바꿔 끼운다 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
