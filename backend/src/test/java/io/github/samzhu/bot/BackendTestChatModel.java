package io.github.samzhu.bot;

import io.github.samzhu.bot.conversation.ConversationTitleGenerator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/** In-process model with configurable Spring AI responses and Gemini usage metadata. */
public final class BackendTestChatModel implements ChatModel, ConversationTitleGenerator {
    private static final ChatResponse DEFAULT_REPLY =
            new ChatResponse(List.of(new Generation(new AssistantMessage("Fixture reply"))));
    private static final Responses DEFAULT_RESPONSES =
            new Responses(DEFAULT_REPLY, Flux.just(DEFAULT_REPLY));

    private final ConcurrentLinkedQueue<Prompt> prompts = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> titleInputs = new ConcurrentLinkedQueue<>();
    private volatile Responses responses = DEFAULT_RESPONSES;
    private volatile TitleReply titleReply;

    public void respondWith(ChatResponse reply, List<ChatResponse> stream) {
        responses = new Responses(reply, Flux.fromIterable(List.copyOf(stream)));
    }

    public void streamWith(Flux<ChatResponse> stream) {
        responses = new Responses(responses.reply(), stream);
    }

    void titleFor(String firstUserMessage, String title) {
        titleReply = new TitleReply(firstUserMessage, title);
    }

    List<String> titleInputs() {
        return List.copyOf(titleInputs);
    }

    @Override
    public String generate(String firstUserMessage) {
        titleInputs.add(firstUserMessage);
        var reply = titleReply;
        if (reply == null || !reply.firstUserMessage().equals(firstUserMessage)) {
            throw new IllegalStateException("Unexpected conversation title invocation");
        }
        return reply.title();
    }

    public void reset() {
        responses = DEFAULT_RESPONSES;
        prompts.clear();
        titleReply = null;
        titleInputs.clear();
    }

    public int invocations() {
        return prompts.size();
    }

    public List<Prompt> prompts() {
        return List.copyOf(prompts);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        prompts.add(prompt);
        return responses.reply();
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> {
            prompts.add(prompt);
            return responses.stream();
        });
    }

    private record TitleReply(String firstUserMessage, String title) {}

    private record Responses(ChatResponse reply, Flux<ChatResponse> stream) {
    }
}
