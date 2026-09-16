package io.ledgerflow.settlement.batch;

import org.springframework.batch.core.partition.Partitioner;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Splits the day's NEW ids into gridSize contiguous ranges so each worker step reads (and saveState()s) its own
 * slice with a paging reader — honest state, unlike threads-cursor's shared cursor.
 */
class IdRangePartitioner implements Partitioner {

    private final JdbcClient db;
    private final LocalDate businessDate;
    private final int gridSize;

    IdRangePartitioner(JdbcClient db, LocalDate businessDate, int gridSize) {
        this.db = db;
        this.businessDate = businessDate;
        this.gridSize = gridSize;
    }

    @Override
    public Map<String, ExecutionContext> partition(int gridSizeHint) {
        Long[] range = db.sql("select min(id) lo, max(id) hi from settlement_item where status = 'NEW' and business_date = :date")
                .param("date", businessDate)
                .query((rs, rowNum) -> new Long[]{(Long) rs.getObject("lo"), (Long) rs.getObject("hi")})
                .single();

        Map<String, ExecutionContext> partitions = new LinkedHashMap<>();
        if (range[0] == null) {   // nothing NEW for this date: one no-op partition rather than a divide-by-zero
            partitions.put("partition0", rangeContext(1, 0));
            return partitions;
        }

        long lo = range[0], hi = range[1];
        long span = (hi - lo + 1 + gridSize - 1) / gridSize;   // ceil, so the last partition is short rather than an extra empty one
        for (int i = 0; i < gridSize; i++) {
            long minId = lo + i * span;
            if (minId > hi) break;
            partitions.put("partition" + i, rangeContext(minId, Math.min(minId + span - 1, hi)));
        }
        return partitions;
    }

    private static ExecutionContext rangeContext(long minId, long maxId) {
        var ctx = new ExecutionContext();
        ctx.putLong("minId", minId);
        ctx.putLong("maxId", maxId);
        return ctx;
    }
}
