package io.github.samzhu.bot.execution.internal.model;

import com.google.genai.Client;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("real-ai")
public class GoogleGenAiManualConfig {

    @Value("${spring.ai.google.genai.api-key:}")
    private String apiKey;

    @Value("${spring.ai.google.genai.chat.model:}")
    private String modelName;

    /*
     * WHY / ARCHITECTURAL LIFECYCLE:
     * 1. com.google.genai.Client implements AutoCloseable, holding underlying HTTP transport channels.
     *    Spring manages its lifecycle and invokes client.close() when destroying the googleGenAiClient bean
     *    (configured via destroyMethod = "close").
     * 2. org.springframework.ai.google.genai.GoogleGenAiChatModel implements DisposableBean,
     *    but its destroy() implementation is a no-op (verified via Spring AI 2.1.0-RC2 bytecode: '0: return').
     *    It does NOT call client.close(), so managing the client lifecycle as a dedicated Spring bean
     *    guarantees proper channel cleanup without risking double-close or connection leaks.
     */
    @Bean(destroyMethod = "close")
    public Client googleGenAiClient() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Fail-fast: 'real-ai' profile requires spring.ai.google.genai.api-key");
        }
        return Client.builder()
                .apiKey(apiKey.trim())
                .build();
    }

    @Bean
    public ChatModel googleGenAiChatModel(Client client) {
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalStateException("Fail-fast: 'real-ai' profile requires explicit spring.ai.google.genai.chat.model in YAML configuration.");
        }

        GoogleGenAiChatOptions options = GoogleGenAiChatOptions.builder()
                .model(modelName.trim())
                .temperature(0.7)
                .build();

        return GoogleGenAiChatModel.builder()
                .genAiClient(client)
                .options(options)
                .build();
    }
}
