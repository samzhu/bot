package io.github.samzhu.bot;

import io.github.samzhu.bot.identity.ProfileGuard;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BotApplication {

	public static void main(String[] args) {
		SpringApplication app = new SpringApplication(BotApplication.class);
		app.addListeners(new ProfileGuard());
		app.run(args);
	}

}
