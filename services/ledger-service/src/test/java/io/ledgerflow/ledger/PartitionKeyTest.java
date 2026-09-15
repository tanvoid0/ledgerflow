package io.ledgerflow.ledger;

import io.ledgerflow.events.WalletRef;
import io.ledgerflow.ledger.application.PlaceHold;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** No database, no Spring: the key is pure arithmetic on what the caller already has. */
class PartitionKeyTest {

    private static final UUID ACCOUNT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void oneWalletKeysByAccountAndLabel() {
        assertThat(PlaceHold.partitionKey(List.of(new WalletRef(ACCOUNT, "A-1"))))
                .isEqualTo(ACCOUNT + ":A-1");
    }

    @Test
    void severalWalletsKeyByTheAccountAlone() {
        assertThat(PlaceHold.partitionKey(List.of(new WalletRef(ACCOUNT, "A-1"), new WalletRef(ACCOUNT, "A-2"))))
                .isEqualTo(ACCOUNT.toString());
    }
}
