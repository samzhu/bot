package io.github.samzhu.bot;

import io.github.samzhu.bot.identity.ProfileGuard;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Automatic Liquibase startup would write before the release preflight or service readiness check.
@SpringBootApplication(exclude = org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration.class)
public class BotApplication {

	public static void main(String[] args) {
		SpringApplication app = new SpringApplication(BotApplication.class);
		app.addListeners(new ProfileGuard());
		app.run(args);
	}

}
