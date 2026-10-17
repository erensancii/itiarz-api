package com.itiarz.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Shared PostgreSQL 17 container initialised with the real {@code infra/postgres/init/01-init.sh}
 * (roles itiarz_owner / itiarz_app) and migrated by Flyway as the owner role.
 */
public final class PostgresSupport {

    public static final String DB_NAME = "itiarz";
    public static final String OWNER_USER = "itiarz_owner";
    public static final String OWNER_PASSWORD = "owner_dev_only_change_me";
    public static final String APP_USER = "itiarz_app";
    public static final String APP_PASSWORD = "app_dev_only_change_me";

    private static PostgreSQLContainer<?> container;

    private PostgresSupport() {
    }

    public static synchronized PostgreSQLContainer<?> container() {
        if (container == null) {
            PostgreSQLContainer<?> c = new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName(DB_NAME)
                    .withCopyFileToContainer(
                            MountableFile.forHostPath(initScript(), 0755),
                            "/docker-entrypoint-initdb.d/01-init.sh");
            c.start();
            Flyway.configure()
                    .dataSource(c.getJdbcUrl(), OWNER_USER, OWNER_PASSWORD)
                    .placeholders(Map.of("appRole", APP_USER))
                    .load()
                    .migrate();
            container = c;
        }
        return container;
    }

    public static Connection appConnection() throws SQLException {
        return DriverManager.getConnection(container().getJdbcUrl(), APP_USER, APP_PASSWORD);
    }

    public static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(container().getJdbcUrl(), OWNER_USER, OWNER_PASSWORD);
    }

    private static Path initScript() {
        for (String candidate : new String[] {
                "../infra/postgres/init/01-init.sh", "infra/postgres/init/01-init.sh"}) {
            Path p = Path.of(candidate).toAbsolutePath().normalize();
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        throw new IllegalStateException(
                "infra/postgres/init/01-init.sh not found relative to " + Path.of("").toAbsolutePath());
    }
}
