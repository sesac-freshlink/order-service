package com.freshlink.order;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** 선착순 대기열 테스트용 Valkey(Redis 호환) 컨테이너. dev·prod ElastiCache와 같은 Valkey 8이다. TLS는 끈다 */
@TestConfiguration(proxyBeanMethods = false)
public class RedisTestcontainersConfiguration {

	@Bean
	@ServiceConnection(name = "redis")
	GenericContainer<?> redisContainer() {
		return new GenericContainer<>(DockerImageName.parse("valkey/valkey:8")).withExposedPorts(6379);
	}

}
