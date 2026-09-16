package io.ledgerflow.settlement.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.listener.SkipListener;
import org.springframework.batch.core.step.skip.SkipPolicy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Where an {@link UnsettleableItemException} lands: skipped out of the chunk, logged, and recorded so the
 * business date's reject count is visible without grepping logs.
 */
@Component
class RejectUnsettleableItems implements SkipPolicy, SkipListener<SettlementItem, SettlementLine> {

    // eleven bad rows is a bad file, not a bad row
    static final int MAX_REJECTS = 10;

    private static final Logger log = LoggerFactory.getLogger(RejectUnsettleableItems.class);

    private final JdbcClient db;

    RejectUnsettleableItems(JdbcClient db) {
        this.db = db;
    }

    @Override
    public boolean shouldSkip(Throwable t, long skipCount) {
        return t instanceof UnsettleableItemException && skipCount < MAX_REJECTS;
    }

    @Override
    public void onSkipInProcess(SettlementItem item, Throwable t) {
        log.warn("rejecting item {}: {}", item.id(), t.getMessage());
        db.sql("""
                insert into settlement_reject (item_id, business_date, reason)
                values (:itemId, :date, :reason)
                on conflict (item_id) do nothing
                """)
                .param("itemId", item.id()).param("date", item.businessDate()).param("reason", t.getMessage())
                .update();
        db.sql("update settlement_item set status = 'REJECTED' where id = :id").param("id", item.id()).update();
    }

    @Override
    public void onSkipInRead(Throwable t) {
        log.error("skip in read: {}", t.getMessage(), t);
    }

    @Override
    public void onSkipInWrite(SettlementLine line, Throwable t) {
        log.error("skip in write, item {}: {}", line.itemId(), t.getMessage(), t);
    }
}
