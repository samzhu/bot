package io.github.samzhu.bot.execution.internal.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samzhu.bot.execution.ExecutionOperations;
import io.github.samzhu.bot.identity.CurrentUserProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/ag-ui")
public class AgUiController {
    private final ExecutionOperations execution;
    private final CurrentUserProvider currentUser;

    // The published AG-UI serializer uses Jackson 2; isolate this parser from Boot's shared mapper
    // and its potentially different Jackson generation and application-wide configuration.
    private final ObjectMapper json = new ObjectMapper();

    public AgUiController(ExecutionOperations execution, CurrentUserProvider currentUser) {
        this.execution = execution;
        this.currentUser = currentUser;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> run(@RequestBody String body) {
        JsonNode input;
        try {
            input = json.readTree(body);
        } catch (Exception e) {
            return badRequest();
        }
        if (input == null || !input.isObject()) {
            return badRequest();
        }

        JsonNode thread = input.path("threadId");
        JsonNode id = input.path("runId");
        JsonNode messages = input.path("messages");
        if (!thread.isTextual() || thread.asText().isBlank() || !id.isTextual()
                || !messages.isArray() || messages.isEmpty()) {
            return badRequest();
        }
        try {
            if (!UUID.fromString(id.asText()).toString().equals(id.asText())) {
                return badRequest();
            }
        } catch (IllegalArgumentException e) {
            return badRequest();
        }
        for (String field : new String[]{"tools", "resume"}) {
            JsonNode value = input.path(field);
            if (!value.isMissingNode() && (!value.isArray() || !value.isEmpty())) {
                return badRequest();
            }
        }

        JsonNode last = messages.get(messages.size() - 1);
        JsonNode content = last.path("content");
        String text;
        // Java core 0.1.1 accepts only string content; AG-UI 1.0 also permits text parts.
        // Parse this subset explicitly so text-only 1.0 clients work without accepting media.
        if (content.isTextual()) {
            text = content.asText();
        } else if (content.isArray()) {
            StringBuilder parts = new StringBuilder();
            for (JsonNode part : content) {
                if (!"text".equals(part.path("type").asText()) || !part.path("text").isTextual()) {
                    return badRequest();
                }
                parts.append(part.path("text").asText());
            }
            text = parts.toString();
        } else {
            return badRequest();
        }
        if (!"user".equals(last.path("role").asText()) || text.isBlank() || text.length() > 10000) {
            return badRequest();
        }

        // Client history and properties are untrusted; only the new user text enters the server-owned prompt.
        var result = execution.startAgUiRun(thread.asText(), id.asText(), text, currentUser.getCurrentUserId());
        return switch (result.type()) {
            case ACCESS_DENIED -> jsonError(404, "CONVERSATION_ACCESS_DENIED");
            case STARTED -> ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(result.emitter());
            case EXISTING -> ResponseEntity.status(409).contentType(MediaType.APPLICATION_JSON).body(Map.of(
                    "code", "DUPLICATE_RUN",
                    "runId", result.existingRun().id(),
                    "status", result.existingRun().status()));
            case CONFLICT -> jsonError(409, "RUN_CONFLICT");
            case SERVICE_UNAVAILABLE -> jsonError(503, "GKE_AGENT_NOT_CONNECTED");
        };
    }

    // HttpAgent requests SSE, but pre-stream rejections are explicit JSON for its error handling.
    private ResponseEntity<?> jsonError(int status, String code) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(Map.of("code", code));
    }

    private ResponseEntity<?> badRequest() {
        return jsonError(400, "INVALID_AG_UI_INPUT");
    }
}
