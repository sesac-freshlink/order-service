package com.freshlink.order.domain.firstcome;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 선착순 대기열 설정 (app.first-come). 값은 11/2 이태우님과 정한 대로다(오픈런 문서 2장 "아직 정하지 않은 것").
 *
 * @param inventoryBaseUrl inventory-service 주소 (클러스터 안 Service 이름). 남은 수량 API를 부른다
 * @param connectTimeout   inventory 연결 타임아웃
 * @param readTimeout      inventory 응답 타임아웃
 * @param ticketTtl        입장권 시간. 이 안에 [주문하기]를 누르지 않으면 사라지고 자리는 다음 사람에게 간다
 * @param idleTimeout      대기 화면의 순번 조회가 이만큼 끊기면 줄에서 뺀다 (탭을 닫고 나간 사람)
 * @param remainingCacheTtl 순번 조회마다 inventory를 부르지 않도록 남은 수량을 파드 메모리에 두는 시간
 * @param reserveGrace     입장권으로 주문을 만든 뒤 inventory가 RESERVE를 반영할 때까지 그 자리를 비워 두지 않는 시간
 */
@ConfigurationProperties(prefix = "app.first-come")
public record FirstComeProperties(
        @DefaultValue("http://inventory-service:8080") String inventoryBaseUrl,
        @DefaultValue("1s") Duration connectTimeout,
        @DefaultValue("2s") Duration readTimeout,
        @DefaultValue("5m") Duration ticketTtl,
        @DefaultValue("30s") Duration idleTimeout,
        @DefaultValue("2s") Duration remainingCacheTtl,
        @DefaultValue("15s") Duration reserveGrace
) {
}
