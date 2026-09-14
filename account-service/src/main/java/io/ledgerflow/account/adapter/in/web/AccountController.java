package io.ledgerflow.account.adapter.in.web;

import io.ledgerflow.account.domain.model.Account;
import io.ledgerflow.account.domain.model.Wallet;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    /** Pinned on purpose: every curl in this project refers to this account. */
    static final UUID DEMO_ACCOUNT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final Account HARDCODED = new Account(DEMO_ACCOUNT, "Demo Arena",
            IntStream.rangeClosed(1, 20)
                     .mapToObj(i -> new Wallet(UUID.randomUUID(), "A-" + i))
                     .toList());

    @GetMapping
    public List<Account> all() {
        return List.of(HARDCODED);
    }
}
