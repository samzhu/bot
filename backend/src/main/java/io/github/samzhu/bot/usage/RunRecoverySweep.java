package io.github.samzhu.bot.usage;

/** One bounded pass of durable Run recovery; unfinished work is retried by the caller. */
public interface RunRecoverySweep {
    void recoverExpiredBatch();
}
