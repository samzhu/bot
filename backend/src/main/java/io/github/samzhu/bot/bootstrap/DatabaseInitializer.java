package io.github.samzhu.bot.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class DatabaseInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    public DatabaseInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void run(ApplicationArguments args) throws Exception {
        log.info("Starting schema initialization and orphan recovery...");

        // 1. Ensure schema_version table exists
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS schema_version (
                script_name VARCHAR(255) PRIMARY KEY,
                executed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
            );
        """);

        // 2. Query executed scripts
        List<String> executed = jdbcTemplate.query(
            "SELECT script_name FROM schema_version",
            (rs, rowNum) -> rs.getString("script_name")
        );
        Set<String> executedSet = new HashSet<>(executed);

        // 3. Scan classpath migration resources
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:db/migration/*.sql");

        Arrays.sort(resources, Comparator.comparing(Resource::getFilename));

        for (Resource resource : resources) {
            String filename = resource.getFilename();
            if (filename != null && !executedSet.contains(filename)) {
                log.info("Applying SQL migration: {}", filename);
                try (InputStream in = resource.getInputStream()) {
                    String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    jdbcTemplate.execute(sql);
                }
                jdbcTemplate.update("INSERT INTO schema_version (script_name) VALUES (?)", filename);
            }
        }

        // 4. Server restart recovery: transition lingering PENDING/RUNNING runs to FAILED
        int recovered = jdbcTemplate.update("""
            UPDATE agent_run
            SET status = 'FAILED',
                error_message = 'SYSTEM_RESTART_ORPHAN',
                updated_at = CURRENT_TIMESTAMP
            WHERE status IN ('PENDING', 'RUNNING');
        """);

        if (recovered > 0) {
            log.warn("Recovered {} orphaned running/pending runs to FAILED on startup.", recovered);
        } else {
            log.info("No orphaned runs detected on startup.");
        }
    }
}
