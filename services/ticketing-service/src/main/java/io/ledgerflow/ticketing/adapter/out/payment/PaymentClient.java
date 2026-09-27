package io.ledgerflow.ticketing.adapter.out.payment;

import io.ledgerflow.starter.web.RequestIdFilter;
import io.ledgerflow.ticketing.config.TicketingProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

/** Pays through the existing saga, over HTTP, with the buyer's own token - no new Keycloak client for ticketing. */
@Component
public class PaymentClient {

    private final RestClient rest;
    private final String wallet;

    public PaymentClient(RestClient.Builder builder, TicketingProperties props) {
        this.rest = builder.baseUrl(props.paymentBaseUrl()).build();
        this.wallet = props.wallet();
    }

    /** Only the field ticketing needs back - not a copy of payment-service's PaymentState. */
    record PaymentRequest(UUID accountId, List<String> wallets, long amountMinor, String currency, String beneficiary) {}

    record PaymentResponse(UUID paymentId) {}

    public UUID pay(String authorization, String requestId, UUID accountId, long amountMinor, String currency, String beneficiary) {
        var request = new PaymentRequest(accountId, List.of(wallet), amountMinor, currency, beneficiary);
        var response = rest.post().uri("/api/v1/payments")
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .header(RequestIdFilter.HEADER, requestId)
                .body(request)
                .retrieve().body(PaymentResponse.class);
        return response.paymentId();
    }
}
