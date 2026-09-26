package io.ledgerflow.settlement.config;

import io.ledgerflow.settlement.adapter.out.account.AccountClient;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration
class AccountClientConfig {

    /** Same reasoning as ledger's: the Kafka listener and the batch step both call out off the request thread. */
    @Bean
    OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository clients, OAuth2AuthorizedClientService authorizedClients) {
        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(clients, authorizedClients);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
        return manager;
    }

    /** Shared with FxRateGateway: one registration, one cached token, for both of settlement's outbound calls. */
    @Bean
    OAuth2ClientHttpRequestInterceptor serviceBearer(OAuth2AuthorizedClientManager manager) {
        var interceptor = new OAuth2ClientHttpRequestInterceptor(manager);
        interceptor.setClientRegistrationIdResolver(request -> "settlement-service");
        return interceptor;
    }

    /** Same timeouts as ledger's client, for the same reason: docs/measurements/step-06-cascade.md. */
    @Bean
    AccountClient accountClient(RestClient.Builder builder, SettlementProperties props, OAuth2ClientHttpRequestInterceptor serviceBearer) {
        var settings = HttpClientSettings.defaults()
                .withConnectTimeout(props.accountConnectTimeout())
                .withReadTimeout(props.accountReadTimeout());
        var rest = builder.baseUrl(props.accountBaseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .requestInterceptor(serviceBearer)
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(rest)).build().createClient(AccountClient.class);
    }
}
