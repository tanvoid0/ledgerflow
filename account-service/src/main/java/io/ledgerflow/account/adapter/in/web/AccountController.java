package io.ledgerflow.account.adapter.in.web;

import io.ledgerflow.account.application.AccountRepository;
import io.ledgerflow.account.domain.model.Account;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountRepository accounts;

    @GetMapping
    public List<Account> all() {
        return accounts.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Account> byId(@PathVariable UUID id) {
        return accounts.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
