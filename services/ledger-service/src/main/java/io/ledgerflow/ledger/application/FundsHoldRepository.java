package io.ledgerflow.ledger.application;

import io.ledgerflow.ledger.domain.model.FundsHold;

import java.util.List;

public interface FundsHoldRepository {
    List<FundsHold> saveAll(List<FundsHold> holds);
}
