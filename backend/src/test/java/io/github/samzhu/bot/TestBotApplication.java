package io.github.samzhu.bot;

import org.springframework.boot.SpringApplication;

public class TestBotApplication {

	public static void main(String[] args) {
		SpringApplication.from(BotApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
