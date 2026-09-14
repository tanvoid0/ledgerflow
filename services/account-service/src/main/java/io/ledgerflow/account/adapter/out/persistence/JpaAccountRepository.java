package io.ledgerflow.account.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface JpaAccountRepository extends JpaRepository<AccountEntity, UUID> {}
