package io.ledgerflow.ticketing.config;

import io.ledgerflow.starter.web.LedgerflowSecurityAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Health and the storefront's GETs open, actuator staff-only, everything else any token: customers are this
 * service's callers, and the controllers check role and owner per method. Overrides the starter's bean rather
 * than fighting it - its own javadoc says that is the contract.
 */
@Configuration
public class TicketingSecurityConfig {   // public: @WebMvcTest imports it from the controller's own package

    @Bean
    SecurityFilterChain ticketingChain(HttpSecurity http) throws Exception {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(LedgerflowSecurityAutoConfiguration.realmRoles());
        http.authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/events", "/api/v1/events/**").permitAll()
                        .requestMatchers("/actuator/**").hasAnyRole(LedgerflowSecurityAutoConfiguration.STAFF)
                        .anyRequest().authenticated())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)));
        return http.build();
    }
}
