package io.github.samzhu.bot;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

public final class PostgresTestImage {

    // Match the development database major version by default.
    private static final String BASELINE = "pgvector/pgvector:pg16";

    private PostgresTestImage() {
    }

    public static PostgreSQLContainer container() {
        // An explicit override allows deliberate testing against another compatible image.
        String image = System.getenv().getOrDefault("BOT_TEST_POSTGRES_IMAGE", BASELINE);
        return new PostgreSQLContainer(DockerImageName.parse(image));
    }
}
