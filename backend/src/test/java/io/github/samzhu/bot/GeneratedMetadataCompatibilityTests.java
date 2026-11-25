package io.github.samzhu.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import liquibase.ChecksumVersion;
import liquibase.changelog.ChangeLogParameters;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Differential evidence against the exact vendor parser and each supported checksum version. */
class GeneratedMetadataCompatibilityTests {
    @Test void everyGeneratedChecksumMatchesFreshVendorParsing() throws Exception {
        var json = new ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode metadata;
        try (var in = getClass().getResourceAsStream("/release/prod.json")) {
            assertNotNull(in); metadata = json.readTree(in);
        }
        String path = "db/changelog/db.changelog-master.yaml";
        for (var version : ChecksumVersion.values()) {
            // Reparse for each version; never trust a previously cached checksum.
            try (var resources = new ClassLoaderResourceAccessor()) {
                var changes = ChangeLogParserFactory.getInstance().getParser(path, resources)
                        .parse(path, new ChangeLogParameters(), resources).getChangeSets();
                assertEquals(changes.size(), metadata.path("schema").size());
                for (int i = 0; i < changes.size(); i++) {
                    var change = changes.get(i); var row = metadata.path("schema").get(i);
                    assertEquals(change.getId(), row.path("id").asText());
                    assertEquals(change.getAuthor(), row.path("author").asText());
                    assertEquals(change.getFilePath(), row.path("path").asText());
                    change.clearCheckSum();
                    assertEquals(change.generateCheckSum(version).toString(), row.path("checksums").path(Integer.toString(version.getVersion())).asText(),
                            change.getId() + " version " + version);
                    assertEquals(change.getValidCheckSums().stream().map(Object::toString).sorted().toList(),
                            json.convertValue(row.path("valid"), json.getTypeFactory().constructCollectionType(java.util.List.class, String.class)));
                }
            }
        }
    }
}
