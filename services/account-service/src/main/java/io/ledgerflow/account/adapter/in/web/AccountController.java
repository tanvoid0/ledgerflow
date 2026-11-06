package io.ledgerflow.account.adapter.in.web;

import io.ledgerflow.account.application.AccountRepository;
import io.ledgerflow.account.application.OnboardAccount;
import io.ledgerflow.account.domain.model.Account;
import io.ledgerflow.account.domain.model.AccountNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountRepository accounts;
    private final OnboardAccount onboard;

    @GetMapping
    public List<Account> all() {
        return accounts.findAll();
    }

    @GetMapping("/{id}")
    public Account byId(@PathVariable UUID id) {
        return accounts.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
    }

    /**
     * Onboards the caller: their own account id is the token's sub, never a path param, so no one onboards
     * anyone else. Idempotent, so ticketing calls it on every checkout. Returns the account either way.
     */
    @PutMapping("/onboard")
    public Account onboardSelf(@AuthenticationPrincipal Jwt jwt) {
        var id = UUID.fromString(jwt.getSubject());
        var name = jwt.getClaimAsString("name");
        if (name == null || name.isBlank()) name = jwt.getClaimAsString("preferred_username");
        onboard.onboard(id, name == null || name.isBlank() ? "customer" : name);
        return accounts.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
    }
}
