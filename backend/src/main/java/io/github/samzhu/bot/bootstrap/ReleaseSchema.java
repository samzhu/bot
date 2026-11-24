package io.github.samzhu.bot.bootstrap;

import liquibase.change.CheckSum;
import liquibase.changelog.ChangeLogParameters;
import liquibase.changelog.ChangeSet;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.HashMap;

/** Read-only comparison against the release changelog; never invokes Liquibase update or validate. */
public final class ReleaseSchema {
    public static final String PATH = "db/changelog/db.changelog-master.yaml";
    private ReleaseSchema() {}

    public static int verifiedPrefix(JdbcTemplate jdbc) throws Exception {
        try (var resources = new ClassLoaderResourceAccessor()) {
            List<ChangeSet> expected = ChangeLogParserFactory.getInstance().getParser(PATH, resources)
                    .parse(PATH, new ChangeLogParameters(), resources).getChangeSets();
            var rows = jdbc.queryForList("select id, author, filename, md5sum, exectype from databasechangelog");
            if (rows.size() > expected.size()) throw mismatch();
            var byIdentity = new HashMap<List<Object>, java.util.Map<String, Object>>();
            for (var row : rows) {
                if (row.get("id") == null || row.get("author") == null || row.get("filename") == null) throw mismatch();
                var key = List.of(row.get("id"), row.get("author"), row.get("filename"));
                if (byIdentity.put(key, row) != null) throw mismatch();
            }
            for (int i = 0; i < rows.size(); i++) {
                var change = expected.get(i);
                var row = byIdentity.get(List.of(change.getId(), change.getAuthor(), change.getFilePath()));
                if (row == null) throw mismatch();
                String checksum = (String) row.get("md5sum");
                if (!"EXECUTED".equals(row.get("exectype")) || checksum == null
                        || !change.isCheckSumValid(CheckSum.parse(checksum))) throw mismatch();
            }
            return expected.size() - rows.size();
        }
    }

    private static IllegalStateException mismatch() {
        return new IllegalStateException("Release provisioning refused: unrecognized Liquibase history");
    }
}
