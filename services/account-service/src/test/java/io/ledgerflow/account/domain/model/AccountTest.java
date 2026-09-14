package io.ledgerflow.account.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AccountTest {

    @Test
    void knowsWhichWalletsItHas() {
        var account = new Account(UUID.randomUUID(), "Demo Arena",
                List.of(new Wallet(UUID.randomUUID(), "A-1")));

        assertThat(account.hasWallet("A-1")).isTrue();
        assertThat(account.hasWallet("ZZ-99")).isFalse();
    }
}
