package io.github.samzhu.bot.usage;

/** Server-owned partition for title background admission. */
@FunctionalInterface
public interface ConversationTitlePartition {
    WorkspaceAdmission.Partition current();
}
