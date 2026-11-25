package io.github.samzhu.bot.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samzhu.bot.pricing.PriceCatalog;
import io.github.samzhu.bot.pricing.PriceSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** The constrained SQL envelope lets readiness read the same payload without executing SQL. */
final class InitializationSql {
    private static final String PREFIX = "INSERT INTO pg_temp.release_initialization (payload) VALUES ($bootstrap$\n";
    private static final String SUFFIX = "\n$bootstrap$::jsonb);\n";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    record Bot(String id, String name, String target, String systemPrompt) {}
    record Payload(Bot bot, String revision, String source, List<PriceCatalog.Entry> entries) {}
    private final String sql;
    private final JsonNode document;

    InitializationSql(String environment) throws Exception {
        if (!List.of("dev", "prod").contains(environment)) throw new IllegalArgumentException("Unknown initialization environment");
        try (var input = InitializationSql.class.getResourceAsStream("/db/initialization/" + environment + ".sql")) {
            if (input == null) throw new IllegalStateException("Missing initialization SQL");
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!sql.startsWith(PREFIX) || !sql.endsWith(SUFFIX)) throw new IllegalStateException("Invalid initialization SQL envelope");
        String payload = sql.substring(PREFIX.length(), sql.length() - SUFFIX.length());
        if (payload.contains("$bootstrap$")) throw new IllegalStateException("Invalid initialization SQL payload");
        document = JSON.readTree(payload);
        if (document == null || !environment.equals(required(document, "environment"))) throw new IllegalStateException("Initialization environment mismatch");
    }

    Payload read() {
        var bot = document.required("bot");
        var catalog = document.required("catalog");
        var entries = new ArrayList<PriceCatalog.Entry>();
        for (var item : catalog.required("entries")) {
            var rates = new LinkedHashMap<String, String>();
            item.required("rates").fields().forEachRemaining(e -> rates.put(e.getKey(), e.getValue().textValue()));
            var metadata = item.get("metadata");
            PriceCatalog.Metadata details = metadata == null || metadata.isNull() ? null : JSON.convertValue(metadata, PriceCatalog.Metadata.class);
            entries.add(new PriceCatalog.Entry(new PriceSnapshot(required(item,"provider"), required(item,"endpoint"),
                    required(item,"model"), required(item,"channel"), required(item,"priceRevision"), rates),
                    Instant.parse(required(item,"fromInclusive")), Instant.parse(required(item,"untilExclusive")), details));
        }
        if (entries.isEmpty()) throw new IllegalStateException("Empty initialization catalog");
        return new Payload(new Bot(required(bot,"id"), required(bot,"name"), required(bot,"target"), required(bot,"systemPrompt")),
                required(catalog,"revision"), required(catalog,"sourceReference"), List.copyOf(entries));
    }

    Payload execute(JdbcTemplate jdbc) throws Exception {
        jdbc.execute("create temporary table release_initialization (payload jsonb not null) on commit drop");
        jdbc.execute(sql);
        var rows = jdbc.queryForList("select payload::text from pg_temp.release_initialization", String.class);
        if (rows.size() != 1 || !document.equals(JSON.readTree(rows.getFirst()))) throw new IllegalStateException("Initialization payload mismatch");
        return read();
    }

    private static String required(JsonNode node, String name) {
        JsonNode value = node.required(name);
        if (!value.isTextual() || value.textValue().isBlank()) throw new IllegalArgumentException("Invalid initialization field: " + name);
        return value.textValue();
    }
}
