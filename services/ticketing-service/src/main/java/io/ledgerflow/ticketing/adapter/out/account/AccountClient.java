package io.ledgerflow.ticketing.adapter.out.account;

import io.ledgerflow.starter.web.RequestIdFilter;
import io.ledgerflow.ticketing.config.TicketingProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Ensures the buyer has an account before checkout, with the buyer's own token - same relay as PaymentClient. */
@Component
public class AccountClient {

    private final RestClient rest;

    public AccountClient(RestClient.Builder builder, TicketingProperties props) {
        this.rest = builder.baseUrl(props.accountBaseUrl()).build();
    }

    /** Idempotent: account-service creates the account + opening balance on the first call, no-ops after. */
    public void ensureAccount(String authorization, String requestId) {
        rest.put().uri("/api/v1/accounts/onboard")
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .header(RequestIdFilter.HEADER, requestId)
                .retrieve().toBodilessEntity();
    }
}
