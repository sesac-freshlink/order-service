package com.freshlink.order.common.config;

import com.freshlink.order.domain.payment.toss.TossPaymentsClient;
import com.freshlink.order.domain.payment.toss.TossProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** 토스 클라이언트. 연결 3초·응답 15초 타임아웃 (넘으면 결과 불명으로 승인 중에 둔다) */
@Configuration
@EnableConfigurationProperties(TossProperties.class)
public class TossConfig {

    @Bean
    public TossPaymentsClient tossPaymentsClient(TossProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        return new TossPaymentsClient(RestClient.builder().requestFactory(factory), properties);
    }
}
