package io.github.samzhu.bot.execution.internal;

import io.github.samzhu.bot.usage.WorkspaceAdmission;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StartupScannerBudgetTests {
    @Test void exhaustedStartupDeadlineDoesNotStartAnotherScanner() {
        var admission=mock(WorkspaceAdmission.class);
        var runs=mock(RunExecutionServiceImpl.class);
        when(admission.scanSettledPromptFailures(any(),any(),anyInt())).thenReturn(new CompletableFuture<>());
        var prompt=new PromptFailureCompletionScanner(admission,runs);
        var superseded=new SupersededBudgetCompletionScanner(admission,runs);
        var scanner=new TerminalRunCompletionScanner(mock(TerminalRunScanRepository.class),admission,
                mock(io.github.samzhu.bot.usage.RunRecoverySweep.class),prompt,superseded);
        long start=System.nanoTime();
        scanner.scanRecoveryBeforeBarrier(start+TimeUnit.MILLISECONDS.toNanos(50));
        assertTrue(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(1),"startup scan must not wait its normal two seconds");
        verify(admission).scanSettledPromptFailures(any(),any(),anyInt());
        verify(admission,never()).scanSupersededNotDispatched(any(),any(),anyInt());
    }
}
