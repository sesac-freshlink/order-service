package com.freshlink.order.common.config;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ShedLock 잠금 (표 shedlock, V1). 파드 2개 중 한 곳만 Outbox 발행기·만료 스케줄러를 돌린다.
 * common v0.2.0은 이 빈이 없으면 앱이 뜨지 않는다 (common README 4-1).
 */
@Configuration
public class LockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }
}
