package com.freshlink.order.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** @Scheduled 켜기 (만료 스케줄러). Outbox 발행기는 common이 자기 스레드로 돌려서 이것과 상관없다 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
