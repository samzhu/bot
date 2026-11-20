package io.github.samzhu.bot.conversation;

/** External title-generation boundary; no production generator is configured in this slice. */
@FunctionalInterface
public interface ConversationTitleGenerator {
    String generate(String firstUserMessage) throws Exception;
}
