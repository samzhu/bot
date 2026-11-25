package io.github.samzhu.bot.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;

/** Read-only comparison against build-generated release expectations. */
public final class ReleaseSchema {
    public static final String PATH = "db/changelog/db.changelog-master.yaml";
    private static final Pattern VERSIONED = Pattern.compile("^([0-9]+):([a-fA-F0-9]{32})$");
    private static final Pattern LEGACY = Pattern.compile("^[a-fA-F0-9]{32}$");
    private ReleaseSchema() {}

    public static int verifiedPrefix(JdbcTemplate jdbc) throws Exception {
        var expected = ReleaseMetadata.load("prod").path("schema");
        var rows = jdbc.queryForList("select id, author, filename, md5sum, exectype from databasechangelog");
        if (rows.size() > expected.size()) throw mismatch();
        var byIdentity = new HashMap<List<Object>, java.util.Map<String, Object>>();
        for (var row : rows) {
            if (row.get("id") == null || row.get("author") == null || row.get("filename") == null) throw mismatch();
            if (byIdentity.put(List.of(row.get("id"), row.get("author"), row.get("filename")), row) != null) throw mismatch();
        }
        for (int i = 0; i < rows.size(); i++) {
            var change = expected.get(i);
            if (!change.path("id").isTextual() || !change.path("author").isTextual() || !change.path("path").isTextual()) throw mismatch();
            var row = byIdentity.get(List.of(change.path("id").asText(), change.path("author").asText(), change.path("path").asText()));
            if (row == null || !"EXECUTED".equals(row.get("exectype")) || !(row.get("md5sum") instanceof String checksum)
                    || !validChecksum(change, checksum)) throw mismatch();
        }
        return expected.size() - rows.size();
    }

    static boolean validChecksum(JsonNode change, String stored) {
        if (stored.isBlank() || !change.path("checksums").isObject() || !change.path("valid").isArray()) return false;
        var matcher = VERSIONED.matcher(stored);
        String version, normalized;
        if (matcher.matches()) {
            try { version = Integer.toString(Integer.parseInt(matcher.group(1))); }
            catch (NumberFormatException ex) { return false; }
            normalized = version + ":" + matcher.group(2);
        } else if (LEGACY.matcher(stored).matches()) {
            version = "1"; normalized = "1:" + stored;
        } else return false;
        for (var entry : change.path("valid")) {
            String valid = entry.asText();
            if ("1:any".equalsIgnoreCase(valid) || "1:all".equalsIgnoreCase(valid) || "1:*".equalsIgnoreCase(valid)) return true;
        }
        var generated = change.path("checksums").path(version);
        if (!generated.isTextual()) return false;
        String current = generated.asText();
        if (current.equals(normalized)) return true;
        for (var entry : change.path("valid"))
            if (current.equals(entry.asText()) || normalized.equals(entry.asText())) return true;
        return false;
    }

    private static IllegalStateException mismatch() {
        return new IllegalStateException("Release provisioning refused: unrecognized Liquibase history");
    }
}
