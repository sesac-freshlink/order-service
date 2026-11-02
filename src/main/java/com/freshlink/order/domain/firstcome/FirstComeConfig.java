package com.freshlink.order.domain.firstcome;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** 선착순 대기열 설정과 inventory 클라이언트. inventory 호출은 연결 1초·응답 2초 타임아웃 */
@Configuration
@EnableConfigurationProperties(FirstComeProperties.class)
public class FirstComeConfig {

    @Bean
    public InventoryFirstComeClient inventoryFirstComeClient(FirstComeProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        return new InventoryFirstComeClient(RestClient.builder().requestFactory(factory),
                properties.inventoryBaseUrl());
    }
}
