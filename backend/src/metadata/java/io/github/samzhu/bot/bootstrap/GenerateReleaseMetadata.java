package io.github.samzhu.bot.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samzhu.bot.pricing.CatalogHash;
import liquibase.ChecksumVersion;
import liquibase.changelog.ChangeLogParameters;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ClassLoaderResourceAccessor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.TreeMap;

/** Build-time tool: only derived, read-only release expectations enter the service. */
public final class GenerateReleaseMetadata {
    private static final String CHANGELOG = "db/changelog/db.changelog-master.yaml";
    private GenerateReleaseMetadata() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Two metadata output directories are required");
        var changes = new ArrayList<Object>();
        try (var resources = new ClassLoaderResourceAccessor()) {
            var log = ChangeLogParserFactory.getInstance().getParser(CHANGELOG, resources)
                    .parse(CHANGELOG, new ChangeLogParameters(), resources);
            for (var change : log.getChangeSets()) {
                var sums = new TreeMap<String, String>();
                for (var version : ChecksumVersion.values()) {
                    // Avoid depending on the cache behavior of a particular Liquibase release.
                    change.clearCheckSum();
                    sums.put(Integer.toString(version.getVersion()), change.generateCheckSum(version).toString());
                }
                var valid = change.getValidCheckSums().stream().map(Object::toString).sorted().toList();
                var row = new LinkedHashMap<String,Object>();
                row.put("id", change.getId()); row.put("author", change.getAuthor()); row.put("path", change.getFilePath());
                row.put("checksums", sums); row.put("valid", valid);
                changes.add(row);
            }
        }
        var json = new ObjectMapper();
        for (String environment : new String[]{"prod", "dev"}) {
            var payload = new InitializationSql(environment).read();
            var output = new LinkedHashMap<String,Object>();
            output.put("schema", changes); output.put("botId", payload.bot().id()); output.put("botTarget", payload.bot().target());
            output.put("revision", payload.revision()); output.put("source", payload.source());
            output.put("contentHash", CatalogHash.contentHash(payload.source(), payload.entries()));
            Path directory = Path.of(args[environment.equals("prod") ? 0 : 1], "release");
            Files.createDirectories(directory);
            json.writeValue(directory.resolve(environment + ".json").toFile(), output);
        }
    }
}
