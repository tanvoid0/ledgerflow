package io.ledgerflow.account.adapter.in.web;

import io.ledgerflow.account.application.AccountRepository;
import io.ledgerflow.account.domain.model.Account;
import io.ledgerflow.account.domain.model.AccountNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountRepository accounts;

    AccountController(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    public List<Account> all() {
        return accounts.findAll();
    }

    @GetMapping("/{id}")
    public Account byId(@PathVariable UUID id) {
        return accounts.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
    }
}