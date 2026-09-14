package io.ledgerflow.events.settlement;

import io.ledgerflow.events.HeldWallet;
import io.ledgerflow.events.Money;

import java.util.List;
import java.util.UUID;

/** Move amount out of each held wallet. Replies: CapturesIssued, or IssueFailed with whatever did move recorded. */
public record IssueCaptures(UUID paymentId, UUID accountId, List<HeldWallet> holds, Money amount) {

    public static final String TYPE = "settlement.IssueCaptures";
    public static final String TOPIC = "ledgerflow.settlement.capture.commands.v1";
}
