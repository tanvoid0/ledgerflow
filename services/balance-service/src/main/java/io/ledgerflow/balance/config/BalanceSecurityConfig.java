package io.ledgerflow.balance.config;

import io.ledgerflow.starter.web.LedgerflowSecurityAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.function.Supplier;

/**
 * The starter's staff-only chain, except an account's own balances: the gateway lets a customer read those,
 * and this service is reachable on its own port too, so it makes the same check itself.
 */
@Configuration
public class BalanceSecurityConfig {   // public: @WebMvcTest imports it from the controller's own package

    @Bean
    SecurityFilterChain balanceChain(HttpSecurity http) throws Exception {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(LedgerflowSecurityAutoConfiguration.realmRoles());
        http.authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/balances/{accountId}", "/api/v1/balances/{accountId}/**")
                                .access(BalanceSecurityConfig::staffOrOwner)
                        .anyRequest().hasAnyRole(LedgerflowSecurityAutoConfiguration.STAFF))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)));
        return http.build();
    }

    /** Same rule as the gateway's: staff read any balance, a customer only its own (its sub is its account id). */
    private static AuthorizationDecision staffOrOwner(Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) {
        var auth = authentication.get();
        boolean staff = auth.getAuthorities().stream().anyMatch(a -> "ROLE_account-read".equals(a.getAuthority()));
        boolean owner = auth instanceof JwtAuthenticationToken jwt
                && context.getVariables().get("accountId").equals(jwt.getToken().getSubject());
        return new AuthorizationDecision(staff || owner);
    }
}
