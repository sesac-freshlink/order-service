package com.freshlink.order.domain.payment.toss;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 토스페이먼츠 연동 설정 (app.toss.*). 시크릿 키는 환경 변수 TOSS_SECRET_KEY로만 받는다 */
@ConfigurationProperties(prefix = "app.toss")
public record TossProperties(
        String baseUrl,
        String secretKey,
        Duration connectTimeout,
        Duration readTimeout
) {
}
