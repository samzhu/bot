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
            case ACCESS_DENIED -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "CONVERSATION_ACCESS_DENIED"));
            case EXISTING -> ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(outcome.existingRun());
            case CONFLICT -> {
                Map<String, Object> body = new java.util.LinkedHashMap<>();
                body.put("error", outcome.conflictMessage());
                if (outcome.existingRun() != null) {
                    body.put("runId", outcome.existingRun().id());
                    body.put("status", outcome.existingRun().status());
                }
                yield ResponseEntity.status(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body);
            }
            case SERVICE_UNAVAILABLE -> {
                Map<String, Object> body = new java.util.LinkedHashMap<>();
                body.put("error", outcome.conflictMessage() != null ? outcome.conflictMessage() : "GKE_AGENT_NOT_CONNECTED");
                body.put("code", "GKE_AGENT_NOT_CONNECTED");
                body.put("status", 503);
                yield ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body);
            }
            case STARTED -> ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(outcome.emitter());
        };
    }

    @PostMapping("/runs/{runId}/cancel")
    public ResponseEntity<?> cancelRun(@PathVariable String runId) {
        String userId = currentUserProvider.getCurrentUserId();
        var outcome = executionOperations.cancelRun(runId, userId);
        if (outcome.accessDenied()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "RUN_ACCESS_DENIED"));
        return ResponseEntity.ok(Map.of(
                "runId", outcome.runId(),
                "status", outcome.finalStatus().name(),
                "cancelled", outcome.cancelled()
        ));
    }
}
