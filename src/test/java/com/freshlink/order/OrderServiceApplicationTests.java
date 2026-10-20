package com.freshlink.order;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"app.queue.commands=test-inventory-commands.fifo", "app.queue.results=test-inventory-results",
		"spring.cloud.aws.sqs.listener.auto-startup=false"})
class OrderServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
