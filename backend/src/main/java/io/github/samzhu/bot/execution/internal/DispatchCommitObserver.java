package io.github.samzhu.bot.execution.internal;

import org.springframework.stereotype.Component;

/** A no-op lifecycle seam for observing the boundary after commit and before provider entry. */
@Component
public class DispatchCommitObserver {
    public void afterCommit(String attemptId) {
        // Intentionally empty: the dispatch policy remains entirely in the invocation boundary.
    }
}
