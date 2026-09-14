package io.ledgerflow.ledger.adapter.out.account;

import lombok.RequiredArgsConstructor;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.util.UUID;

/**
 * Its own bean on purpose: @Retryable works through a proxy, and a method that
 * calls a retryable sibling on `this` never goes through it. The gateway calls
 * this bean, so the retry is real.
 */
@Component
@RequiredArgsConstructor
class AccountRetries {

    private final AccountClient client;

    /** A read, so safe to repeat. One try plus two retries, 200ms apart, then give up. */
    @Retryable(maxRetries = 2, delay = 200, includes = RestClientException.class)
    AccountClient.AccountView account(UUID accountId) {
        return client.account(accountId);
    }
}
