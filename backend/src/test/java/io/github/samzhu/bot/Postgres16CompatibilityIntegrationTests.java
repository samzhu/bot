package io.github.samzhu.bot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@BackendIntegrationTest
class Postgres16CompatibilityIntegrationTests {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void sharedPostgres16SupportsVectorCastAndPreservesJsonbIntegers() {
        String vectorInstalled = "select exists(select 1 from pg_extension where extname = 'vector')";
        var installedBefore = jdbc.queryForObject(vectorInstalled, Boolean.class);
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.executeWithoutResult(status -> {
            // Keep the temporary extension visible to this test's SQL without committing shared schema changes.
            status.setRollbackOnly();
            jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
            assertThat(jdbc.queryForObject("SELECT '[1,2,3]'::vector::text", String.class)).isEqualTo("[1,2,3]");
            assertThat(jdbc.queryForObject("SELECT '{\"n\":9007199254740993}'::jsonb->>'n'", String.class))
                    .isEqualTo("9007199254740993");
            assertThat(jdbc.queryForObject("show server_version_num", Integer.class)).isBetween(160000, 169999);
        });
        assertThat(jdbc.queryForObject(vectorInstalled, Boolean.class)).isEqualTo(installedBefore);
    }
}
