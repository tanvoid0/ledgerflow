package io.ledgerflow.ledger.adapter.out.account;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Everything the rest of the app uses to reach account goes through here: bounded
 * retry for a blip (AccountRetries), and a last-known-good answer when every
 * attempt has failed.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AccountGateway {

    private final AccountRetries retries;
    private final Map<UUID, AccountClient.AccountView> lastKnownGood = new ConcurrentHashMap<>();

    /**
     * Degrade instead of fail: serve the cached view when account is down. The risk is
     * holding a wallet that was just deleted, which beats refusing all business while
     * account recovers. Step 13's read model is the grown-up version of this cache.
     */
    public AccountClient.AccountView accountOrLastKnown(UUID accountId) {
        try {
            var view = retries.account(accountId);
            lastKnownGood.put(accountId, view);
            return view;
        } catch (RestClientException e) {
            var cached = lastKnownGood.get(accountId);
            if (cached == null) throw e;             // nothing to fall back to
            log.warn("account unavailable ({}), serving cached view for {}", e.getMessage(), accountId);
            return cached;
        }
    }
}
