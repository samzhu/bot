package io.github.samzhu.bot.bot.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for GKE Autonomous Agent execution and simulation mode.
 *
 * TODO: Currently a local development Spring AI simulation boundary (bot.gke.execution-mode=SIMULATED/OFF).
 * When GCP project, official agent endpoint, credentials/identity, and event protocols are established,
 * replace with Gemini Enterprise Agent Platform integration (retaining GKE deployment architecture,
 * not turning into arbitrary managed runtime). Preserves cancel/dedup/session contracts.
 */
@Component
public class GkeProperties {

    public enum ExecutionMode {
        OFF,
        SIMULATED
    }

    private final ExecutionMode executionMode;

    public GkeProperties(@Value("${bot.gke.execution-mode:OFF}") ExecutionMode executionMode) {
        this.executionMode = executionMode != null ? executionMode : ExecutionMode.OFF;
    }

    public ExecutionMode getExecutionMode() {
        return executionMode;
    }

    public boolean isSimulated() {
        return executionMode == ExecutionMode.SIMULATED;
    }
}
