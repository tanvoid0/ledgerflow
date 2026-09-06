package io.ledgerflow.account;

import io.ledgerflow.account.application.AccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
class AccountRepositoryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired AccountRepository accounts;

    @Test
    void seededAccountIsReadable() {
        var demo = UUID.fromString("11111111-1111-1111-1111-111111111111");

        var account = accounts.findById(demo).orElseThrow();

        assertThat(account.name()).isEqualTo("Demo Arena");
        assertThat(account.wallets()).hasSize(20);
        assertThat(account.hasWallet("A-12")).isTrue();
    }
}
