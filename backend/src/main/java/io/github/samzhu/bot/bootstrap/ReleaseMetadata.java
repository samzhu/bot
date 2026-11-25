package io.github.samzhu.bot.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;

/** Reads build-generated expectations; it never executes initialization SQL. */
final class ReleaseMetadata {
    private static final ObjectMapper JSON = new ObjectMapper();
    private ReleaseMetadata() {}

    static JsonNode load(String environment) throws IOException {
        if (!"prod".equals(environment) && !"dev".equals(environment)) throw new IllegalArgumentException("Unknown release environment");
        try (var input = ReleaseMetadata.class.getResourceAsStream("/release/" + environment + ".json")) {
            if (input == null) throw new IllegalStateException("Release metadata is missing");
            var result = JSON.readTree(input);
            if (result == null || !result.path("schema").isArray() || result.path("schema").isEmpty())
                throw new IllegalStateException("Release metadata is invalid");
            for (String name : new String[]{"botId", "botTarget", "revision", "source", "contentHash"})
                if (!result.path(name).isTextual() || result.path(name).asText().isBlank()) throw new IllegalStateException("Release metadata is invalid");
            return result;
        }
    }
}
