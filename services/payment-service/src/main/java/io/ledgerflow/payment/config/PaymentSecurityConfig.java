package io.ledgerflow.payment.config;

import io.ledgerflow.starter.web.LedgerflowSecurityAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The starter's staff-only chain, except starting a payment: ticketing relays the buyer's own token here,
 * and PaymentController only lets a customer pay from its own account.
 */
@Configuration
public class PaymentSecurityConfig {   // public: @WebMvcTest imports it from the controller's own package

    @Bean
    SecurityFilterChain paymentChain(HttpSecurity http) throws Exception {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(LedgerflowSecurityAutoConfiguration.realmRoles());
        http.authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/payments").authenticated()
                        .anyRequest().hasAnyRole(LedgerflowSecurityAutoConfiguration.STAFF))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)));
        return http.build();
    }
}
