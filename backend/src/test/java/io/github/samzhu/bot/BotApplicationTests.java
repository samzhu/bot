package io.github.samzhu.bot;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

@BackendIntegrationTest
class BotApplicationTests {

	@Test
	void contextLoads(@Autowired Environment environment) throws Exception {
		int port = environment.getProperty("local.server.port", Integer.class, 0);
		assertThat(port).as("the application serves probes on a real HTTP port").isPositive();
		var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health/readiness"))
				.timeout(Duration.ofSeconds(5)).GET().build();
		try (var client = HttpClient.newHttpClient()) {
			var response = client.send(request, HttpResponse.BodyHandlers.ofString());
			assertThat(response.statusCode()).isEqualTo(200);
			assertThat(response.body()).contains("\"status\":\"UP\"");
		}
	}

}
