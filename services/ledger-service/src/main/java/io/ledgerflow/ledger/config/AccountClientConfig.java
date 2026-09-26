package io.ledgerflow.ledger.config;

import io.ledgerflow.ledger.adapter.out.account.AccountClient;
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

    /**
     * DefaultOAuth2AuthorizedClientManager needs an HttpServletRequest to hang the authorized client on;
     * PlaceHold's Kafka listener calls account-service off the request thread, so this is the service-account
     * variant instead - one client-credentials token per registration, cached and reused.
     */
    @Bean
    OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository clients, OAuth2AuthorizedClientService authorizedClients) {
        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(clients, authorizedClients);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
        return manager;
    }

    /**
     * The timeouts are the highest-value lines in this service. Without them a frozen
     * account-service fills ledger's thread pool with waits that never end and takes
     * ledger down with it - measured in docs/measurements/step-06-cascade.md.
     */
    @Bean
    AccountClient accountClient(RestClient.Builder builder, LedgerProperties props, OAuth2AuthorizedClientManager manager) {
        var settings = HttpClientSettings.defaults()
                .withConnectTimeout(props.accountConnectTimeout())
                .withReadTimeout(props.accountReadTimeout());

        var bearer = new OAuth2ClientHttpRequestInterceptor(manager);
        bearer.setClientRegistrationIdResolver(request -> "ledger-service");

        var rest = builder
                .baseUrl(props.accountBaseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .requestInterceptor(bearer)
                .build();

        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(rest))
                .build()
                .createClient(AccountClient.class);
    }
}
