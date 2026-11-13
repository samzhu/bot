package io.github.samzhu.bot.usage.internal.admission;

import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicBoolean;

/** Process-local barrier; durable facts are checked again before admission opens. */
@Component
final class StartupRecoveryState {
    private final AtomicBoolean rebuilt=new AtomicBoolean();
    boolean rebuilt() { return rebuilt.get(); }
    void markRebuilt() { rebuilt.set(true); }
}
