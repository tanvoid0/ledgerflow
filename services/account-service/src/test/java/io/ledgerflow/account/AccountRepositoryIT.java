package io.ledgerflow.account;

import io.ledgerflow.account.application.AccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AccountRepositoryIT {

    @Autowired
    AccountRepository accounts;

    @Test
    void seededAccountIsReadable() {
        var demo = UUID.fromString("11111111-1111-1111-1111-111111111111");

        var account = accounts.findById(demo).orElseThrow();

        assertThat(account.name()).isEqualTo("Demo Arena");
        assertThat(account.wallets()).hasSize(21);   // A-1..A-20 plus TREASURY
        assertThat(account.hasWallet("A-12")).isTrue();
        assertThat(account.hasWallet("TREASURY")).isTrue();
    }
}
