package io.ledgerflow.ledger.config;

import io.ledgerflow.ledger.adapter.out.account.AccountClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration
class AccountClientConfig {

    @Bean
    AccountClient accountClient(RestClient.Builder builder, LedgerProperties props) {
        // Deliberately no timeout yet. Measured and fixed in the next change.
        var rest = builder.baseUrl(props.accountBaseUrl()).build();
        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(rest))
                .build()
                .createClient(AccountClient.class);
    }
}
