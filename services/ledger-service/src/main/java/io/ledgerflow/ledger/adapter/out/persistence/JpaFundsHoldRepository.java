package io.ledgerflow.ledger.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface JpaFundsHoldRepository extends JpaRepository<FundsHoldEntity, UUID> {}
