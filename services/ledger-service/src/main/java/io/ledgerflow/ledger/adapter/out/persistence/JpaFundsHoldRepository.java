package io.ledgerflow.ledger.adapter.out.persistence;

import io.ledgerflow.ledger.domain.model.FundsHold;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.UUID;

interface JpaFundsHoldRepository extends JpaRepository<FundsHoldEntity, UUID> {

    // FOR UPDATE: two closers racing on one reference cannot both find the holds open
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<FundsHoldEntity> findAllByReferenceAndStatus(UUID reference, FundsHold.Status status);
}
