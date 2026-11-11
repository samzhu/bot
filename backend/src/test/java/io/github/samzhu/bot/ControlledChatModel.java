package io.github.samzhu.bot;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test fixture replacement for MockChatModel via Spring Test @TestBean.
 * Provides fine-grained control over model streaming emission, hold-before-complete,
 * and deterministic synchronization latches for concurrent race verification.
 */
public class ControlledChatModel implements ChatModel, StreamingChatModel {

    public enum Mode {
        AUTOMATIC,
        HOLD,
        BLOCKING_ITERATOR
    }

    private static final ControlledChatModel INSTANCE = new ControlledChatModel();

    public static ControlledChatModel getInstance() {
        return INSTANCE;
    }

    private final List<Prompt> recordedPrompts = new CopyOnWriteArrayList<>();

    private volatile Mode mode = Mode.AUTOMATIC;
    private volatile Sinks.Many<ChatResponse> sink = Sinks.many().multicast().onBackpressureBuffer();
    private volatile CountDownLatch subscribedLatch = new CountDownLatch(1);
    private volatile CountDownLatch cancelledLatch = new CountDownLatch(1);
    private volatile CountDownLatch finishedLatch = new CountDownLatch(1);

    private volatile CountDownLatch blockLatch = null;
    private volatile CountDownLatch blockEnteredLatch = null;
    private volatile CountDownLatch blockDisposedLatch = null;

    public ControlledChatModel() {
    }

    public synchronized void reset() {
        recordedPrompts.clear();
        mode = Mode.AUTOMATIC;
        sink = Sinks.many().multicast().onBackpressureBuffer();
        subscribedLatch = new CountDownLatch(1);
        cancelledLatch = new CountDownLatch(1);
        finishedLatch = new CountDownLatch(1);
        blockLatch = null;
        blockEnteredLatch = null;
        blockDisposedLatch = null;
    }

    public synchronized void setBlockingIteratorMode(CountDownLatch blockLatch, CountDownLatch enteredLatch, CountDownLatch disposedLatch) {
        this.mode = Mode.BLOCKING_ITERATOR;
        this.blockLatch = blockLatch;
        this.blockEnteredLatch = enteredLatch;
        this.blockDisposedLatch = disposedLatch;
    }

    public synchronized void setHoldMode(boolean hold) {
        this.mode = hold ? Mode.HOLD : Mode.AUTOMATIC;
        this.sink = Sinks.many().multicast().onBackpressureBuffer();
        this.subscribedLatch = new CountDownLatch(1);
        this.cancelledLatch = new CountDownLatch(1);
        this.finishedLatch = new CountDownLatch(1);
    }

    public List<Prompt> getRecordedPrompts() {
        return Collections.unmodifiableList(recordedPrompts);
    }

    public Prompt getLastPrompt() {
        if (recordedPrompts.isEmpty()) {
            return null;
        }
        return recordedPrompts.get(recordedPrompts.size() - 1);
    }

    public boolean awaitSubscribed(long timeout, TimeUnit unit) throws InterruptedException {
        return subscribedLatch.await(timeout, unit);
    }

    public boolean awaitCancelled(long timeout, TimeUnit unit) throws InterruptedException {
        return cancelledLatch.await(timeout, unit);
    }

    public boolean awaitFinished(long timeout, TimeUnit unit) throws InterruptedException {
        return finishedLatch.await(timeout, unit);
    }

    public void emitChunk(String text) {
        sink.tryEmitNext(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
    }

    public void complete() {
        sink.tryEmitComplete();
    }

    public void error(Throwable t) {
        sink.tryEmitError(t);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        recordedPrompts.add(prompt);
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
        recordedPrompts.add(prompt);
        String userText = extractUserText(prompt);

        if (userText.contains("[ERROR]")) {
            return Flux.error(new RuntimeException("Simulated upstream streaming error"));
        }

        if (mode == Mode.HOLD) {
            return sink.asFlux()
                    .doOnSubscribe(sub -> subscribedLatch.countDown())
                    .doOnCancel(() -> cancelledLatch.countDown())
                    .doFinally(signalType -> finishedLatch.countDown());
        }

        if (mode == Mode.BLOCKING_ITERATOR) {
            Iterable<ChatResponse> blockingIterable = () -> new java.util.Iterator<>() {
                @Override
                public boolean hasNext() {
                    return true;
                }

                @Override
                public ChatResponse next() {
                    if (blockEnteredLatch != null) {
                        blockEnteredLatch.countDown();
                    }
                    try {
                        if (blockLatch != null) {
                            blockLatch.await(10, TimeUnit.SECONDS);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("Unblocked chunk"))));
                }
            };

            return Flux.fromIterable(blockingIterable)
                    .doOnCancel(() -> {
                        if (blockDisposedLatch != null) {
                            blockDisposedLatch.countDown();
                        }
                    })
                    .doFinally(signal -> {
                        if (blockDisposedLatch != null) {
                            blockDisposedLatch.countDown();
                        }
                    });
        }

        String[] chunks = new String[] {
            "Hello, ",
            "this is a deterministic ",
            "mock streaming response for: ",
            userText
        };

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
