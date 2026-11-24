package io.github.samzhu.bot.bootstrap;

import liquibase.integration.spring.SpringLiquibase;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/** Migration seam for the one-off release process. */
public class ReleaseMigration {
    public static final String CHANGELOG = "classpath:db/changelog/db.changelog-master.yaml";
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public ReleaseMigration(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    public void run() throws Exception {
        boolean history = Boolean.TRUE.equals(jdbc.queryForObject(
                "select to_regclass('databasechangelog') is not null", Boolean.class));
        boolean legacyTable = Boolean.TRUE.equals(jdbc.queryForObject(
                "select to_regclass('schema_version') is not null", Boolean.class));
        boolean legacy = legacyTable && jdbc.queryForObject("select count(*) from schema_version", Integer.class) > 0;
        if (history) ReleaseSchema.verifiedPrefix(jdbc);
        int rows = history ? jdbc.queryForObject("select count(*) from databasechangelog", Integer.class) : 0;
        if (legacy) {
            try (var resources = new liquibase.resource.ClassLoaderResourceAccessor()) {
                var expected = liquibase.parser.ChangeLogParserFactory.getInstance().getParser(ReleaseSchema.PATH, resources)
                        .parse(ReleaseSchema.PATH, new liquibase.changelog.ChangeLogParameters(), resources).getChangeSets();
                LegacyBaseline.verify(jdbc, expected);
                // Liquibase 5.0.4 sync emits EXECUTED, the same value as update. The verified
                // legacy receipts and catalog provide the independent adoption evidence.
                if (rows < LegacyBaseline.SCRIPTS.size()) {
                    // Each vendor command commits independently; a later invocation revalidates the legacy baseline and resumes.
                    try (var connection = dataSource.getConnection();
                         var lb = new liquibase.Liquibase(ReleaseSchema.PATH, resources,
                                 new liquibase.database.jvm.JdbcConnection(connection))) {
                        for (int i = rows; i < LegacyBaseline.SCRIPTS.size(); i++) {
                            lb.markNextChangeSetRan(new liquibase.Contexts(), new liquibase.LabelExpression());
                            ReleaseSchema.verifiedPrefix(jdbc);
                            int actual = jdbc.queryForObject("select count(*) from databasechangelog", Integer.class);
                            if (actual != i + 1)
                                throw new IllegalStateException("Release provisioning refused: legacy sync did not record expected prefix");
                        }
                    }
                }
            }
        } else if (rows == 0 && Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from pg_class c join pg_namespace n on n.oid=c.relnamespace
                  where n.nspname=current_schema() and c.relkind in ('r','p','v','m','S','f')
                    and c.relname not in ('databasechangelog', 'databasechangeloglock'))
                """, Boolean.class))) {
            throw new IllegalStateException("Release provisioning refused: unknown or legacy database requires verified baseline");
        }
        var liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog(CHANGELOG);
        liquibase.afterPropertiesSet();
    }
}
