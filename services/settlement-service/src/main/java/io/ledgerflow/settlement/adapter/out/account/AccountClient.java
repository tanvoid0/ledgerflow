package io.ledgerflow.settlement.adapter.out.account;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.List;
import java.util.UUID;

/** What settlement needs from account-service: wallet ids for labels, and the one endpoint that moves money. */
public interface AccountClient {

    @GetExchange("/api/v1/accounts/{id}")
    AccountView account(@PathVariable UUID id);

    @PostExchange("/api/v1/transfers")
    TransferResponse transfer(@RequestHeader("Idempotency-Key") String idempotencyKey, @RequestBody TransferRequest body);

    record AccountView(UUID id, List<WalletView> wallets) {
        public UUID walletId(String label) {
            return wallets.stream().filter(w -> label.equals(w.label())).map(WalletView::id).findFirst()
                    .orElseThrow(() -> new IllegalStateException("account " + id + " has no wallet " + label));
        }
    }

    record WalletView(UUID id, String label) {}

    record TransferRequest(UUID fromWalletId, UUID toWalletId, long amountMinor, String currency, String description) {}

    record TransferResponse(UUID entryId) {}
}
