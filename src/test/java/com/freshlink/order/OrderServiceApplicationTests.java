package com.freshlink.order;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.queue.commands=test-inventory-commands.fifo")
class OrderServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
