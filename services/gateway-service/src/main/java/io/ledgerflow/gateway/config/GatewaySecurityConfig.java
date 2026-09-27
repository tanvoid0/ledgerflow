package io.ledgerflow.gateway.config;

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
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;
import java.util.function.Supplier;

/**
 * Overrides the starter's chain (its own javadoc says that is the contract). Customers hold realm tokens
 * too, so a token alone is not staff: the storefront's paths take any token and leave owner checks to the
 * service behind, everything else needs a staff role. Plus CORS: the web app is a different origin from
 * every routed service, and the browser's preflight carries no token at all.
 */
@Configuration
class GatewaySecurityConfig {

    @Bean
    SecurityFilterChain gatewayChain(HttpSecurity http) throws Exception {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(LedgerflowSecurityAutoConfiguration.realmRoles());
        http.authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers(CorsUtils::isPreFlightRequest).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/events", "/api/v1/events/**").permitAll()
                        .requestMatchers("/actuator/**").hasAnyRole("account-read", "dev")
                        .requestMatchers("/api/v1/dev/**").hasRole("dev")
                        // the storefront: payment and ticketing check the owner and role themselves
                        .requestMatchers(HttpMethod.POST, "/api/v1/payments").authenticated()
                        .requestMatchers("/api/v1/orders", "/api/v1/orders/**", "/api/v1/events", "/api/v1/events/**").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/balances/{accountId}", "/api/v1/balances/{accountId}/**")
                                .access(GatewaySecurityConfig::staffOrOwner)
                        .requestMatchers("/api/v1/stream").hasAnyRole("account-read", "dev")
                        .requestMatchers(HttpMethod.GET, "/api/**").hasAnyRole("account-read", "ledger-write")
                        .anyRequest().hasRole("ledger-write"))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)));
        return http.build();
    }

    /** Staff read any balance; a customer only its own, and the realm makes a customer's sub its account id. */
    private static AuthorizationDecision staffOrOwner(Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) {
        var auth = authentication.get();
        boolean staff = auth.getAuthorities().stream().anyMatch(a -> "ROLE_account-read".equals(a.getAuthority()));
        boolean owner = auth instanceof JwtAuthenticationToken jwt
                && context.getVariables().get("accountId").equals(jwt.getToken().getSubject());
        return new AuthorizationDecision(staff || owner);
    }

    /** The web app is normally same-origin via nginx; this only covers dev (Vite on 5173) and the packaged web app's own port. */
    private CorsConfigurationSource corsConfigurationSource() {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:5173", "http://localhost:8089"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-Id", "Idempotency-Key"));
        config.setExposedHeaders(List.of("X-Request-Id", "X-Projection"));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/**", config);
        return source;
    }
}
