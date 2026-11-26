package io.github.samzhu.bot;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/** In-process model with configurable Spring AI responses and Gemini usage metadata. */
public final class BackendTestChatModel implements ChatModel {
    private static final ChatResponse DEFAULT_REPLY =
            new ChatResponse(List.of(new Generation(new AssistantMessage("Fixture reply"))));
    private static final Responses DEFAULT_RESPONSES =
            new Responses(DEFAULT_REPLY, List.of(DEFAULT_REPLY));

    private final AtomicInteger invocations = new AtomicInteger();
    private volatile Responses responses = DEFAULT_RESPONSES;

    public void respondWith(ChatResponse reply, List<ChatResponse> stream) {
        responses = new Responses(reply, List.copyOf(stream));
    }

    public void reset() {
        responses = DEFAULT_RESPONSES;
        invocations.set(0);
    }

    public int invocations() {
        return invocations.get();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        invocations.incrementAndGet();
        return responses.reply();
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> {
            invocations.incrementAndGet();
            return Flux.fromIterable(responses.stream());
        });
    }

    private record Responses(ChatResponse reply, List<ChatResponse> stream) {
    }
}
