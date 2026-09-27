package io.ledgerflow.ticketing.adapter.in.web;

import io.ledgerflow.starter.web.RequestIdFilter;
import io.ledgerflow.ticketing.application.Ticketing;
import io.ledgerflow.ticketing.application.Ticketing.Order;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
class OrdersController {

    private static final String ACCOUNT_READ = "ROLE_account-read";

    private final Ticketing ticketing;

    /** Checkout relays the caller's own bearer to payment-service: no service identity for ticketing to hold. */
    @PostMapping("/{id}/checkout")
    @PreAuthorize("hasRole('customer')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Order checkout(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt, @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        return ticketing.checkout(id, UUID.fromString(jwt.getSubject()), authorization, MDC.get(RequestIdFilter.MDC_KEY));
    }

    @GetMapping
    List<Order> mine(@AuthenticationPrincipal Jwt jwt) {
        return ticketing.ordersForAccount(UUID.fromString(jwt.getSubject()));
    }

    @GetMapping("/all")
    @PreAuthorize("hasRole('account-read')")
    List<Order> all(@RequestParam(required = false) String status, @RequestParam(defaultValue = "50") int limit) {
        return ticketing.ordersAll(status, Math.clamp(limit, 1, 200));
    }

    @GetMapping("/{id}")
    Order byId(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        var order = ticketing.order(id).orElseThrow(() -> new ErrorResponseException(HttpStatus.NOT_FOUND));
        var owner = order.accountId().equals(UUID.fromString(jwt.getSubject()));
        var canReadAny = auth.getAuthorities().stream().anyMatch(a -> ACCOUNT_READ.equals(a.getAuthority()));
        if (!owner && !canReadAny) throw new ErrorResponseException(HttpStatus.NOT_FOUND);
        return order;
    }
}
