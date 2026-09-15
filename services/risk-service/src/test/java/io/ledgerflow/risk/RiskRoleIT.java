package io.ledgerflow.risk;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What infra/compose/init-databases.sh does to a fresh volume, run for real: risk can open its own
 * database and no other service's. A plain container, started by hand rather than through the JUnit5
 * extension (not published for Testcontainers 2.x yet) - Ryuk reaps it on JVM exit either way.
 */
class RiskRoleIT {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17")
            .withUsername("ledgerflow").withPassword("ledgerflow").withDatabaseName("account")
            .withCopyFileToContainer(MountableFile.forHostPath("../../infra/compose/init-databases.sh"), "/docker-entrypoint-initdb.d/init.sh");

    @BeforeAll
    static void startContainer() {
        postgres.start();
    }

    @Test
    void riskCanOpenItsOwnDatabase() throws SQLException {
        try (var c = DriverManager.getConnection(urlFor("risk"), "risk", "risk")) {
            assertThat(c.isValid(2)).isTrue();
        }
    }

    @Test
    void riskCannotOpenTheLedgersDatabase() {
        assertThatThrownBy(() -> DriverManager.getConnection(urlFor("ledger"), "risk", "risk"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("permission denied for database");
    }

    private static String urlFor(String database) {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + database;
    }
}
