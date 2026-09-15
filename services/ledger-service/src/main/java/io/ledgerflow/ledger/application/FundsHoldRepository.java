package io.ledgerflow.ledger.application;

import io.ledgerflow.ledger.domain.model.FundsHold;

import java.util.List;
import java.util.UUID;

public interface FundsHoldRepository {
    List<FundsHold> saveAll(List<FundsHold> holds);

    /** Moves every open hold with the reference to the status; returns them. Idempotent: a second call finds none open. */
    List<FundsHold> closeAll(UUID reference, FundsHold.Status to);
}
