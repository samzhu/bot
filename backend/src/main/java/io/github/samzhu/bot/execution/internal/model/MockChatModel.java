package io.github.samzhu.bot.execution.internal.model;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

@Component
@Profile("(local | test) & !real-ai")
public class MockChatModel implements ChatModel, StreamingChatModel {

    @Override
    public ChatResponse call(Prompt prompt) {
        String userText = extractUserText(prompt);
        if (userText.contains("[ERROR]")) {
            throw new RuntimeException("Simulated upstream model error");
        }
        return new ChatResponse(List.of(new Generation(new AssistantMessage("Mock deterministic reply to: " + userText))));
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return null;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        String userText = extractUserText(prompt);
        if (userText.contains("[ERROR]")) {
            return Flux.error(new RuntimeException("Simulated upstream streaming error"));
        }
        if (userText.contains("[PARTIAL_ERROR]")) {
            return Flux.fromArray(new String[] { "Hello, ", "this is a partial mock message before failure " })
                    .delayElements(Duration.ofMillis(30))
                    .map(chunk -> new ChatResponse(List.of(new Generation(new AssistantMessage(chunk)))))
                    .concatWith(Flux.error(new RuntimeException("Simulated upstream streaming error after partial generation")));
        }

        String[] chunks = new String[] {
            "Hello, ",
            "this is a deterministic ",
            "mock streaming response for: ",
            userText
        };

        if (userText.contains("[DELAY]")) {
            return Flux.fromArray(chunks)
                    .delaySubscription(Duration.ofMillis(400))
                    .delayElements(Duration.ofMillis(50))
                    .map(chunk -> new ChatResponse(List.of(new Generation(new AssistantMessage(chunk)))));
        }

        return Flux.fromArray(chunks)
                .delayElements(Duration.ofMillis(30))
                .map(chunk -> new ChatResponse(List.of(new Generation(new AssistantMessage(chunk)))));
    }

    private String extractUserText(Prompt prompt) {
        if (prompt == null || prompt.getInstructions() == null) return "";
        String lastText = "";
        for (Message m : prompt.getInstructions()) {
            if (m instanceof UserMessage um) {
                lastText = um.getText();
            }
        }
        return lastText;
    }
}
