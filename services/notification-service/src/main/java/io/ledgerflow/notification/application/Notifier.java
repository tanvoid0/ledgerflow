package io.ledgerflow.notification.application;

import io.ledgerflow.events.ledger.FundsHeld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class Notifier {

    private static final Logger log = LoggerFactory.getLogger(Notifier.class);

    private final JdbcClient db;

    Notifier(JdbcClient db) {
        this.db = db;
    }

    /** No mail server yet: the row stands in for the email. Called twice, it sends twice. */
    public void send(FundsHeld held) {
        db.sql("INSERT INTO sent_notifications (hold_id) VALUES (:holdId)")
                .param("holdId", held.holdId())
                .update();
        log.info("emailed the customer: {} {} held on {} until {} (hold {})",
                held.totalAmount().currency(), held.totalAmount().minorUnits(),
                held.wallets().getFirst().label(), held.expiresAt(), held.holdId());
    }
}
