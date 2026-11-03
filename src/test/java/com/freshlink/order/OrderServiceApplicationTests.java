package com.freshlink.order;

import com.freshlink.order.domain.scheduler.OrderExpiryScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"app.queue.commands=test-inventory-commands.fifo", "app.queue.results=test-inventory-results", "app.queue.notifications=test-notification-events",
		"spring.cloud.aws.sqs.listener.auto-startup=false"})
class OrderServiceApplicationTests {

	@Autowired
	ApplicationContext context;

	/** 기본 설정이면 만료 스케줄러 빈이 뜬다 (다른 통합 테스트는 끈다) */
	@Test
	void contextLoads() {
		assertThat(context.getBeansOfType(OrderExpiryScheduler.class)).hasSize(1);
	}

}
