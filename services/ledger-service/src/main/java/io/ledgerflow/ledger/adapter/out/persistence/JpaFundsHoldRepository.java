package io.ledgerflow.ledger.adapter.out.persistence;

import io.ledgerflow.ledger.domain.model.FundsHold;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

interface JpaFundsHoldRepository extends JpaRepository<FundsHoldEntity, UUID> {

    @Modifying
    @Query("UPDATE FundsHoldEntity h SET h.status = :to WHERE h.reference = :reference AND h.status = :from")
    int closeAll(UUID reference, FundsHold.Status from, FundsHold.Status to);
}
