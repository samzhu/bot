package io.github.samzhu.bot.execution.internal.web;

import io.github.samzhu.bot.execution.ExecutionOperations;
import io.github.samzhu.bot.identity.CurrentUserProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class RunController {

    private final ExecutionOperations executionOperations;
    private final CurrentUserProvider currentUserProvider;

    public RunController(ExecutionOperations executionOperations, CurrentUserProvider currentUserProvider) {
        this.executionOperations = executionOperations;
        this.currentUserProvider = currentUserProvider;
    }

    public record StartRunRequest(
            @NotBlank @Size(max = 128) String clientRequestId,
            @NotBlank @Size(max = 10000) String message
    ) {}

    @PostMapping(value = "/conversations/{conversationId}/runs", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> startRun(
            @PathVariable String conversationId,
            @Valid @RequestBody StartRunRequest request) {

        String userId = currentUserProvider.getCurrentUserId();
        var outcome = executionOperations.startRun(conversationId, request.clientRequestId(), request.message(), userId);

        return switch (outcome.type()) {
            case EXISTING -> ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(outcome.existingRun());
            case CONFLICT -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", outcome.conflictMessage()));
            case STARTED -> ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(outcome.emitter());
        };
    }

    @PostMapping("/runs/{runId}/cancel")
    public ResponseEntity<?> cancelRun(@PathVariable String runId) {
        String userId = currentUserProvider.getCurrentUserId();
        var outcome = executionOperations.cancelRun(runId, userId);
        return ResponseEntity.ok(Map.of(
                "runId", outcome.runId(),
                "status", outcome.finalStatus().name(),
                "cancelled", outcome.cancelled()
        ));
    }
}
