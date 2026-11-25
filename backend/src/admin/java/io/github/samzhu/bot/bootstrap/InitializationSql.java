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
    private final JsonNode sqlDocument;

    InitializationSql(String environment) throws Exception {
        if (!List.of("dev", "prod").contains(environment)) throw new IllegalArgumentException("Unknown initialization environment");
        try (var input = InitializationSql.class.getResourceAsStream("/db/initialization/" + environment + ".sql")) {
            if (input == null) throw new IllegalStateException("Missing initialization SQL");
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!sql.startsWith(PREFIX) || !sql.endsWith(SUFFIX)) throw new IllegalStateException("Invalid initialization SQL envelope");
        String payload = sql.substring(PREFIX.length(), sql.length() - SUFFIX.length());
        if (payload.contains("$bootstrap$")) throw new IllegalStateException("Invalid initialization SQL payload");
        sqlDocument = JSON.readTree(payload);
        if (sqlDocument == null || !environment.equals(required(sqlDocument, "environment"))) throw new IllegalStateException("Initialization environment mismatch");
        var options = new org.yaml.snakeyaml.LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try (var input = InitializationSql.class.getResourceAsStream("/pricing/catalog.yaml")) {
            if (input == null) throw new IllegalStateException("Missing pricing catalog");
            var yaml = new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(options));
            Object loaded = yaml.load(input);
            JsonNode catalog = JSON.valueToTree(loaded);
            fields(catalog, "environments", "entries");
            var environments = catalog.required("environments");
            fields(environments, "dev", "prod");
            var allEntries = catalog.required("entries");
            if (!allEntries.isObject() || allEntries.isEmpty()) throw new IllegalArgumentException("Invalid catalog entries");
            for (String name : List.of("dev", "prod")) {
                var selection = environments.required(name);
                fields(selection, "revision", "sourceReference", "entries");
                required(selection, "revision");
                required(selection, "sourceReference");
                var references = selection.required("entries");
                if (!references.isArray() || references.isEmpty()) throw new IllegalArgumentException("Invalid catalog selection");
                var unique = new java.util.HashSet<String>();
                for (var reference : references) {
                    if (!reference.isTextual() || reference.textValue().isBlank() || !unique.add(reference.textValue())
                            || !allEntries.has(reference.textValue())) throw new IllegalArgumentException("Invalid catalog selection");
                }
            }
            JsonNode selected = environments.required(environment);
            var result = ((com.fasterxml.jackson.databind.node.ObjectNode) sqlDocument).deepCopy();
            var output = JSON.createObjectNode();
            output.put("revision", required(selected, "revision"));
            output.put("sourceReference", required(selected, "sourceReference"));
            var entries = output.putArray("entries");
            for (var name : selected.required("entries")) entries.add(allEntries.required(name.textValue()));
            result.set("catalog", output);
            document = result;
        }
    }

    Payload read() {
        var bot = document.required("bot");
        var catalog = document.required("catalog");
        var entries = new ArrayList<PriceCatalog.Entry>();
        for (var item : catalog.required("entries")) {
            fields(item, "provider", "endpoint", "model", "channel", "priceRevision", "fromInclusive", "untilExclusive", "metadata", "rates");
            var rates = new LinkedHashMap<String, String>();
            var rateNode = item.required("rates");
            if (!rateNode.isObject() || rateNode.isEmpty()) throw new IllegalArgumentException("Invalid catalog rate");
            rateNode.fields().forEachRemaining(e -> {
                if (e.getKey().isBlank() || !e.getValue().isTextual() || !e.getValue().textValue().matches("[0-9]+(?:\\.[0-9]+)?"))
                    throw new IllegalArgumentException("Invalid catalog rate");
                rates.put(e.getKey(), e.getValue().textValue());
            });
            var metadata = item.get("metadata");
            if (metadata == null || metadata.isNull()) throw new IllegalArgumentException("Invalid catalog metadata");
            fields(metadata, "serviceTier", "tokenUnit", "sources", "verifiedDate", "storageRate", "storageUnit");
            var storageRate = metadata.get("storageRate");
            var storageUnit = metadata.get("storageUnit");
            if (storageRate != null && !storageRate.isNull()) {
                if (!storageRate.isTextual() || !storageRate.textValue().matches("[0-9]+(?:\\.[0-9]+)?")
                        || storageUnit == null || !"USD_PER_1000000_TOKEN_HOURS".equals(storageUnit.textValue()))
                    throw new IllegalArgumentException("Invalid catalog metadata");
            } else if (storageUnit != null && !storageUnit.isNull()) throw new IllegalArgumentException("Invalid catalog metadata");
            required(metadata, "serviceTier");
            java.time.LocalDate.parse(required(metadata, "verifiedDate"));
            if (!"USD_PER_1000000_TOKENS".equals(required(metadata, "tokenUnit")))
                throw new IllegalArgumentException("Invalid catalog metadata");
            var sources = metadata.required("sources");
            if (!sources.isArray() || sources.isEmpty()) throw new IllegalArgumentException("Invalid catalog metadata");
            for (var source : sources) {
                if (!source.isTextual()) throw new IllegalArgumentException("Invalid catalog metadata");
                var uri = java.net.URI.create(source.textValue());
                if (!"https".equals(uri.getScheme()) || uri.getHost() == null)
                    throw new IllegalArgumentException("Invalid catalog metadata");
            }
            PriceCatalog.Metadata details = JSON.convertValue(metadata, PriceCatalog.Metadata.class);
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
        if (rows.size() != 1 || !sqlDocument.equals(JSON.readTree(rows.getFirst()))) throw new IllegalStateException("Initialization payload mismatch");
        return read();
    }

    private static String required(JsonNode node, String name) {
        JsonNode value = node.required(name);
        if (!value.isTextual() || value.textValue().isBlank()) throw new IllegalArgumentException("Invalid initialization field: " + name);
        return value.textValue();
    }

    private static void fields(JsonNode node, String... names) {
        if (!node.isObject()) throw new IllegalArgumentException("Invalid catalog fields");
        var expected = java.util.Set.of(names);
        node.fieldNames().forEachRemaining(name -> {
            if (!expected.contains(name)) throw new IllegalArgumentException("Invalid catalog fields");
        });
    }
}
