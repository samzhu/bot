package io.github.samzhu.bot.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DatabaseInitializer {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    public DatabaseInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(rollbackFor = Exception.class)
    public void run(ApplicationArguments args) throws Exception {
        // Serialize release processes until the migration transaction commits.
        jdbcTemplate.execute("SELECT pg_advisory_xact_lock(7620261124)");
        applyMigrations("classpath:db/migration/*.sql", "schema_version");
    }

    public int applyMigrations(String locationPattern, String versionTable) throws Exception {
        jdbcTemplate.execute(String.format("""
            CREATE TABLE IF NOT EXISTS %s (
                script_name VARCHAR(255) PRIMARY KEY,
                executed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
            );
        """, versionTable));

        List<String> executed = jdbcTemplate.query(
            "SELECT script_name FROM " + versionTable,
            (rs, rowNum) -> rs.getString("script_name")
        );
        Set<String> executedSet = new HashSet<>(executed);

        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources(locationPattern);
        sortResources(resources);

        int applied = 0;
        for (Resource resource : resources) {
            String filename = resource.getFilename();
            if (filename != null && !executedSet.contains(filename)) {
                log.info("Applying SQL migration: {}", filename);
                try (InputStream in = resource.getInputStream()) {
                    String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    jdbcTemplate.execute(sql);
                }
                jdbcTemplate.update("INSERT INTO " + versionTable + " (script_name) VALUES (?)", filename);
                applied++;
            }
        }
        return applied;
    }

    private static void sortResources(Resource[] resources) {
        Arrays.sort(resources, (r1, r2) -> {
            int v1 = extractNumericVersion(r1.getFilename());
            int v2 = extractNumericVersion(r2.getFilename());
            if (v1 != v2) {
                return Integer.compare(v1, v2);
            }
            return String.valueOf(r1.getFilename()).compareTo(String.valueOf(r2.getFilename()));
        });
    }

    private static int extractNumericVersion(String filename) {
        if (filename == null) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^V(\\d+)__").matcher(filename);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return 0;
    }
}
