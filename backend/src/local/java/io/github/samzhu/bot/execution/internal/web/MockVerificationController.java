package io.github.samzhu.bot.execution.internal.web;

import io.github.samzhu.bot.execution.internal.model.MockChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Local-only capability endpoint for ordinary verification scripts to reliably confirm
 * that the backend is running with a safe, deterministic MockChatModel and NOT a paid/live AI model.
 *
 * Isolated in 'localDevelopment' sourceSet so it is NEVER packaged into production bootJar or container images.
 * Placed within execution.internal.web to respect Modulith module boundaries without exposing internal models.
 * Fails closed (HTTP 404) if 'real-ai' profile is active or if ChatModel is not MockChatModel.
 */
@RestController
@RequestMapping("/api/internal/verification")
@Profile("local")
public class MockVerificationController {

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final Environment environment;

    public MockVerificationController(ObjectProvider<ChatModel> chatModelProvider, Environment environment) {
        this.chatModelProvider = chatModelProvider;
        this.environment = environment;
    }

    @GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getStatus() {
        if (environment.matchesProfiles("real-ai")) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel instanceof MockChatModel) {
            return ResponseEntity.ok(Map.of(
                    "status", "SAFE_MOCK",
                    "mock", true,
                    "modelClass", "MockChatModel"
            ));
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
}
