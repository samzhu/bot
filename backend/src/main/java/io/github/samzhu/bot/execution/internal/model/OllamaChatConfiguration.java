package io.github.samzhu.bot.execution.internal.model;

import java.net.URI;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("local & !mock-ai & !test & !prod")
@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "ollama")
class OllamaChatConfiguration {
    @Bean ChatModel ollamaChatModel(@Value("${spring.ai.ollama.base-url:http://localhost:11434}") String baseUrl,
                                   @Value("${spring.ai.ollama.chat.model:}") String model) {
        if (model.isBlank()) throw new IllegalStateException("Ollama requires an explicit chat model");
        URI uri;
        try { uri = URI.create(baseUrl); }
        catch (IllegalArgumentException ex) { throw new IllegalStateException("Ollama requires a valid HTTP origin", ex); }
        if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))
                || uri.getPort() > 65535) {
            throw new IllegalStateException("Ollama requires a valid HTTP origin");
        }
        return OllamaChatModel.builder().ollamaApi(OllamaApi.builder().baseUrl(baseUrl).build())
                .options(OllamaChatOptions.builder().model(model).build()).build();
    }
}
