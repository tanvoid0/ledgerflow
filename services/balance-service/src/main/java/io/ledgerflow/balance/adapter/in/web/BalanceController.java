package io.ledgerflow.balance.adapter.in.web;

import io.ledgerflow.balance.application.Balances;
import io.ledgerflow.balance.application.WalletBalance;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/balances")
@RequiredArgsConstructor
class BalanceController {

    /** Longer than the projection's usual lag by a wide margin, shorter than a user's patience. */
    private static final Duration READ_YOUR_WRITE = Duration.ofSeconds(2);

    private final Balances balances;

    record AccountBalances(UUID accountId, List<WalletBalance> wallets) {}

    @GetMapping("/{accountId}")
    AccountBalances account(@PathVariable UUID accountId) {
        var wallets = balances.account(accountId);
        if (wallets.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no balances for account " + accountId);
        return new AccountBalances(accountId, wallets);
    }

    /**
     * {@code after}: the X-Request-Id a write answered with. The response waits until the projection has applied
     * that request to this wallet, or gives up after two seconds and says so in X-Projection. ADR 0002.
     */
    @GetMapping("/{accountId}/{label}")
    ResponseEntity<WalletBalance> wallet(@PathVariable UUID accountId, @PathVariable String label,
                                         @RequestParam(required = false) String after) throws InterruptedException {
        var caughtUp = after == null || awaitApplied(after, accountId, label);
        var wallet = balances.wallet(accountId, label)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no balance for " + accountId + "/" + label));
        return ResponseEntity.ok().header("X-Projection", caughtUp ? "caught-up" : "lagging").body(wallet);
    }

    private boolean awaitApplied(String token, UUID accountId, String label) throws InterruptedException {
        var deadline = System.nanoTime() + READ_YOUR_WRITE.toNanos();
        while (!balances.applied(token, accountId, label)) {
            if (System.nanoTime() > deadline) return false;
            Thread.sleep(25);   // ponytail: a poll, not keyspace notifications; revisit if reads-after-writes ever dominate
        }
        return true;
    }
}
