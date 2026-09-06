package io.ledgerflow.account.application;

import io.ledgerflow.account.domain.model.Account;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository {
    List<Account> findAll();
    Optional<Account> findById(UUID id);
}
