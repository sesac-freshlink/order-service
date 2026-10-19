package com.freshlink.order.common.config;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * created_at·updated_at 자동 기록. 메인 클래스가 아닌 별도 설정으로 둔다 (@WebMvcTest가 JPA 없이 떠야 한다).
 * 기본 제공자는 LocalDateTime을 주므로 OffsetDateTime 필드용으로 서울 시각을 직접 준다.
 */
@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaAuditingConfig {

    @Bean
    public DateTimeProvider auditingDateTimeProvider() {
        return () -> Optional.of(OffsetDateTime.now(ZoneId.of("Asia/Seoul")));
    }
}
