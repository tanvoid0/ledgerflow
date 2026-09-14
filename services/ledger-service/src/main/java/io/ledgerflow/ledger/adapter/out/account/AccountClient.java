package io.ledgerflow.ledger.adapter.out.account;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;

import java.util.List;
import java.util.UUID;

/** What ledger needs from account-service, written down as a type. Timeouts and retries attach here. */
public interface AccountClient {

    @GetExchange("/api/v1/accounts/{id}")
    AccountView account(@PathVariable UUID id);

    /** Only the fields ledger actually uses - not a copy of account's model. */
    record AccountView(UUID id, String name, List<WalletView> wallets) {
        public boolean hasWallet(String label) {
            return wallets.stream().anyMatch(w -> label.equals(w.label()));
        }
    }

    record WalletView(UUID id, String label) {}
}
