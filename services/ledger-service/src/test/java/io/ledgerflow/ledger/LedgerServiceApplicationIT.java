package io.ledgerflow.ledger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LedgerServiceApplicationIT {

    @Autowired
    JdbcClient db;

    @Test
    void migratesItsOwnSchemaAndNothingElse() {
        var tables = db.sql("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'
                """).query(String.class).list();

        // no accounts, no postings: ledger owns none of that
        assertThat(tables).containsExactlyInAnyOrder("funds_holds", "outbox", "processed_events");
    }
}
