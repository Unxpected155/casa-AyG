package com.casaayg.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HexFormat;
import java.security.SecureRandom;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Levanta un Postgres real (la misma imagen y el mismo script de roles que compose.yaml)
 * y arranca la app completa: Flyway migra como casaayg_owner y la app se conecta
 * como casaayg_app. Un solo contenedor para todos los tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class PostgresTestBase {

    // Contraseñas aleatorias por ejecución: no hay secretos fijos en los tests.
    protected static final String OWNER_PASSWORD = randomSecret();
    protected static final String APP_PASSWORD = randomSecret();
    protected static final String TAX_ID_KEY = randomSecret();
    protected static final String GMAIL_TOKEN_KEY = randomSecret();

    // La MISMA imagen que compose.yaml, fijada por digest (postgres:18.7).
    protected static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse(
                "postgres@sha256:b9f6b8ff03b62f3cfd9d9ba3fe56226a9991c79d3fb46fd46536263bf02be553"))
            .withDatabaseName("casaayg")
            .withCopyFileToContainer(
                MountableFile.forHostPath("../docker/postgres/initdb/01-roles.sh"),
                "/docker-entrypoint-initdb.d/01-roles.sh")
            .withCopyToContainer(Transferable.of(OWNER_PASSWORD), "/run/secrets/db_owner_password")
            .withCopyToContainer(Transferable.of(APP_PASSWORD), "/run/secrets/db_app_password");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("db_app_password", () -> APP_PASSWORD);
        registry.add("db_owner_password", () -> OWNER_PASSWORD);
        registry.add("tax_id_key", () -> TAX_ID_KEY);
        registry.add("gmail_token_key", () -> GMAIL_TOKEN_KEY);
        // Los tests no leen ningún buzón por su cuenta.
        registry.add("casaayg.invoices.mailbox-enabled", () -> "false");
        // Los tests no llaman a Hacienda; el emulador de passkeys usa el origen http://localhost.
        registry.add("casaayg.exchange-rate.enabled", () -> "false");
        registry.add("casaayg.security.origins", () -> "http://localhost");
    }

    /** Conexión como el rol de la aplicación (el que importa para la seguridad). */
    protected static Connection asApp() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "casaayg_app", APP_PASSWORD);
    }

    /** Conexión como superusuario: solo para preparar datos de prueba. */
    protected static Connection asSuperuser() throws SQLException {
        Connection c = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        c.createStatement().execute("SET search_path = casaayg");
        return c;
    }

    private static String randomSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
