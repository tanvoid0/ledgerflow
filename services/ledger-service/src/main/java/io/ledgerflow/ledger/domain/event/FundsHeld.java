package io.ledgerflow.ledger.domain.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Past tense: this states a fact that has already happened. Whoever listens is not our concern. */
public record FundsHeld(UUID holdId, UUID accountId, List<String> wallets, Instant expiresAt) {}
