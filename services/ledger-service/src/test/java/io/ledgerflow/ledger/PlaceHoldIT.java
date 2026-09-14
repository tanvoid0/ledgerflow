package io.ledgerflow.ledger;

import io.ledgerflow.events.Money;
import io.ledgerflow.ledger.adapter.out.account.AccountClient;
import io.ledgerflow.ledger.application.PlaceHold;
import io.ledgerflow.ledger.domain.model.FundsHold;
import io.ledgerflow.ledger.domain.model.UnknownWalletException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/** The payoff for declaring the dependency as a type: replace one bean, never open a socket. */
@Testcontainers
@SpringBootTest
class PlaceHoldIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired
    PlaceHold placeHold;

    @Autowired
    JdbcClient db;

    @MockitoBean
    AccountClient account;      // account-service does not have to be running

    private static final UUID ACCOUNT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void refusesAWalletAccountDoesNotKnowAndWritesNothing() {
        when(account.account(ACCOUNT)).thenReturn(
                new AccountClient.AccountView(ACCOUNT, "Demo Arena",
                        List.of(new AccountClient.WalletView(UUID.randomUUID(), "A-12"))));

        assertThatThrownBy(() -> placeHold.place(ACCOUNT, List.of("A-12", "ZZ-99"), Money.gbp(100)))
                .isInstanceOf(UnknownWalletException.class)
                .hasMessageContaining("ZZ-99");

        assertThat(db.sql("SELECT count(*) FROM funds_holds").query(Long.class).single()).isZero();
    }

    @Test
    void holdsAKnownWallet() {
        when(account.account(ACCOUNT)).thenReturn(
                new AccountClient.AccountView(ACCOUNT, "Demo Arena",
                        List.of(new AccountClient.WalletView(UUID.randomUUID(), "A-12"))));

        var holds = placeHold.place(ACCOUNT, List.of("A-12"), Money.gbp(4500));

        assertThat(holds).hasSize(1);
        assertThat(holds.getFirst().status()).isEqualTo(FundsHold.Status.HELD);
        assertThat(holds.getFirst().amount()).isEqualTo(Money.gbp(4500));
        assertThat(db.sql("SELECT status FROM funds_holds WHERE id = :id").param("id", holds.getFirst().id())
                .query(String.class).single()).isEqualTo("HELD");
    }
}
