package io.github.samzhu.bot;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

public final class PostgresTestImage {

    // Pin the verified PG18.6 multi-platform image until pgvector publishes the current PG18 patch.
    private static final String BASELINE = "pgvector/pgvector@sha256:5895949dfcdf308eb0cd31c950c93c14895c77f33fbedfecf1fa511ab1d4bcd2";

    private PostgresTestImage() {
    }

    public static PostgreSQLContainer container() {
        // The explicit override permits an isolated PG16 compatibility smoke without changing the default baseline.
        String image = System.getenv().getOrDefault("BOT_TEST_POSTGRES_IMAGE", BASELINE);
        return new PostgreSQLContainer(DockerImageName.parse(image));
    }
}
