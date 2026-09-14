package io.ledgerflow.ledger.application;

import io.ledgerflow.events.Money;
import io.ledgerflow.ledger.adapter.out.account.AccountClient;
import io.ledgerflow.ledger.domain.model.FundsHold;
import io.ledgerflow.ledger.domain.model.UnknownWalletException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PlaceHold {

    private static final Duration HOLD_FOR = Duration.ofMinutes(10);

    private final AccountClient account;
    private final FundsHoldRepository holds;

    /** Ledger must not hold a wallet that account has never heard of: hence the network call. */
    @Transactional
    public List<FundsHold> place(UUID accountId, List<String> walletCodes, Money amount) {
        var accountView = account.account(accountId);

        for (var label : walletCodes) {
            if (!accountView.hasWallet(label)) throw new UnknownWalletException(accountId, label);
        }

        var expiry = Instant.now().plus(HOLD_FOR);
        return holds.saveAll(walletCodes.stream()
                .map(label -> FundsHold.hold(accountId, label, amount, expiry))
                .toList());
    }
}
