package io.ledgerflow.settlement.config;

import io.ledgerflow.settlement.adapter.out.account.AccountClient;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration
class AccountClientConfig {

    /** Same timeouts as ledger's client, for the same reason: docs/measurements/step-06-cascade.md. */
    @Bean
    AccountClient accountClient(RestClient.Builder builder, SettlementProperties props) {
        var settings = HttpClientSettings.defaults()
                .withConnectTimeout(props.accountConnectTimeout())
                .withReadTimeout(props.accountReadTimeout());
        var rest = builder.baseUrl(props.accountBaseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(rest)).build().createClient(AccountClient.class);
    }
}
