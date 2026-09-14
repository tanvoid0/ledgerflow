package io.ledgerflow.ledger.config;

import io.ledgerflow.ledger.adapter.out.account.AccountClient;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration
class AccountClientConfig {

    /**
     * The timeouts are the highest-value lines in this service. Without them a frozen
     * account-service fills ledger's thread pool with waits that never end and takes
     * ledger down with it - measured in docs/measurements/step-06-cascade.md.
     */
    @Bean
    AccountClient accountClient(RestClient.Builder builder, LedgerProperties props) {
        var settings = HttpClientSettings.defaults()
                .withConnectTimeout(props.accountConnectTimeout())
                .withReadTimeout(props.accountReadTimeout());

        var rest = builder
                .baseUrl(props.accountBaseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .build();

        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(rest))
                .build()
                .createClient(AccountClient.class);
    }
}
