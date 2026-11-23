package io.github.samzhu.bot.execution.internal.model;

import com.google.genai.Client;
import com.google.genai.types.ClientOptions;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import io.github.samzhu.bot.conversation.ConversationTitleGenerator;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Separate title client: no retries or redirects may repeat an uncertain provider request. */
@Configuration(proxyBeanMethods = false)
@Profile("local & !mock-ai & !test & !prod")
@ConditionalOnProperty(name = "bot-ai-provider", havingValue = "gemini")
class GeminiConversationTitleConfiguration {
    @Bean(name = "conversationTitleClient", destroyMethod = "close", defaultCandidate = false)
    Client conversationTitleClient(@Value("${bot-google-api-key:}") String key) {
        if (key == null || key.isBlank()) throw new IllegalStateException("Title client requires the configured Developer API key");
        var transport = TitleHttpTransport.builder().callTimeout(Duration.ofSeconds(20)).build();
        var http = HttpOptions.builder().timeout(20000).retryOptions(HttpRetryOptions.builder().attempts(1).build()).build();
        return Client.builder().enterprise(false).apiKey(key.trim()).httpOptions(http)
                .clientOptions(ClientOptions.builder().customHttpClient(transport).build()).build();
    }

    @Bean ConversationTitleGenerator conversationTitleGenerator(@Qualifier("conversationTitleClient") Client client) {
        return new GeminiConversationTitleGenerator(client);
    }
}
