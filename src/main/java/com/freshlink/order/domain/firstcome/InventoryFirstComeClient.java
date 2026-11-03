package com.freshlink.order.domain.firstcome;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * inventory 선착순 남은 수량 {@code GET /stores/{storeId}/products/{productId}/first-come} (inventory #61).
 * gateway로 열려 있지 않아 order가 클러스터 안에서 부른다. 예외를 던지지 않고 결과를 분류해 돌려준다
 */
public class InventoryFirstComeClient {

    private static final Logger log = LoggerFactory.getLogger(InventoryFirstComeClient.class);

    private final RestClient restClient;

    /** builder에는 타임아웃이 걸린 요청 팩토리가 붙어 있어야 한다 (FirstComeConfig) */
    public InventoryFirstComeClient(RestClient.Builder builder, String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    public FirstComeLookup lookup(Long storeId, Long productId) {
        try {
            return restClient.get()
                    .uri("/stores/{storeId}/products/{productId}/first-come", storeId, productId)
                    .exchange((request, response) -> {
                        if (response.getStatusCode().is2xxSuccessful()) {
                            Body body = response.bodyTo(Body.class);
                            if (body == null) {
                                return FirstComeLookup.unknown();
                            }
                            return FirstComeLookup.firstCome(body.limit(), Math.max(0, body.remaining()));
                        }
                        if (response.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                            return FirstComeLookup.notFirstCome();
                        }
                        log.warn("선착순 남은 수량 조회 실패 storeId={} productId={} status={}",
                                storeId, productId, response.getStatusCode().value());
                        return FirstComeLookup.unknown();
                    });
        } catch (RestClientException e) {
            log.warn("선착순 남은 수량 조회 결과를 알 수 없다 storeId={} productId={} cause={}",
                    storeId, productId, e.getClass().getSimpleName());
            return FirstComeLookup.unknown();
        }
    }

    /** inventory FirstComeResponse 중 쓰는 칸 */
    record Body(Long productId, int limit, int used, int remaining) {
    }
}
