package io.ledgerflow.account.adapter.out.persistence;

import io.ledgerflow.account.application.AccountRepository;
import io.ledgerflow.account.domain.model.Account;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
class AccountRepositoryAdapter implements AccountRepository {

    private final JpaAccountRepository jpa;
    private final AccountMapper mapper;

    @Override
    public List<Account> findAll() {
        return jpa.findAll().stream().map(mapper::toDomain).toList();
    }

    @Override
    public Optional<Account> findById(UUID id) {
        return jpa.findById(id).map(mapper::toDomain);
    }
}
