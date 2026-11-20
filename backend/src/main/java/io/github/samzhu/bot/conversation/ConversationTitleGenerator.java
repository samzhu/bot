package io.github.samzhu.bot.conversation;

/** External title-generation boundary; implementations must not log input or output content. */
@FunctionalInterface
public interface ConversationTitleGenerator {
    record Usage(Integer input, Integer cached, Integer output, Integer thought, Integer tool, Integer total) {}
    record Result(String title, String responseId, Usage usage) {}

    String generate(String firstUserMessage) throws Exception;

    /** Existing test fakes may provide text only; absence of usage remains unknown. */
    default Result generateResult(String firstUserMessage) throws Exception {
        return new Result(generate(firstUserMessage), null, null);
    }
}
