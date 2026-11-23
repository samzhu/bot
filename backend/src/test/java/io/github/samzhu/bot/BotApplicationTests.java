package io.github.samzhu.bot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@Import(TestcontainersConfiguration.class)
@io.github.samzhu.bot.UseTestFakes
@SpringBootTest
@ActiveProfiles("test")
class BotApplicationTests {

	@Test
	void contextLoads() {
	}

}
