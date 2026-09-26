package io.ledgerflow.settlement.adapter.out.issuer;

import io.ledgerflow.settlement.config.SettlementProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** What settlement needs from issuer-service: the fx uplift, in basis points, to add on top of the card fee. */
@Component
public class FxRateGateway {

    private final RestClient rest;

    public FxRateGateway(RestClient.Builder builder, SettlementProperties props, OAuth2ClientHttpRequestInterceptor serviceBearer) {
        var settings = HttpClientSettings.defaults()
                .withConnectTimeout(props.accountConnectTimeout())
                .withReadTimeout(props.accountReadTimeout());
        this.rest = builder.baseUrl(props.issuerBaseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .requestInterceptor(serviceBearer)
                .build();
    }

    /** No retry here: retry (on transient failure only) belongs to the batch step that calls this. */
    public int upliftBps(String currency) {
        return rest.get().uri("/api/v1/fx/{currency}", currency).retrieve().body(Integer.class);
    }
}
