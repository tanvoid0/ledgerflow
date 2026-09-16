package io.ledgerflow.settlement.batch;

import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.database.JdbcBatchItemWriter;
import org.springframework.batch.infrastructure.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.infrastructure.item.support.ClassifierCompositeItemWriter;
import org.springframework.batch.infrastructure.item.support.builder.ClassifierCompositeItemWriterBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Date;

/** GBP lines settle same-day into settlement_line; everything else waits for FX and lands in settlement_line_fx. */
@Configuration
class SettlementWriterConfig {

    @Bean
    JdbcBatchItemWriter<SettlementLine> foreignCurrencyLineWriter(DataSource ds) {
        return new JdbcBatchItemWriterBuilder<SettlementLine>()
                .dataSource(ds)
                .sql("""
                        insert into settlement_line_fx (item_id, business_date, merchant_id, currency, gross_minor, fee_minor, net_minor)
                        values (?, ?, ?, ?, ?, ?, ?)
                        on conflict (item_id) do nothing
                        """)
                .itemPreparedStatementSetter((line, ps) -> {
                    ps.setLong(1, line.itemId());
                    ps.setDate(2, Date.valueOf(line.businessDate()));
                    ps.setString(3, line.merchantId());
                    ps.setString(4, line.currency());
                    ps.setLong(5, line.grossMinor());
                    ps.setLong(6, line.feeMinor());
                    ps.setLong(7, line.netMinor());
                })
                .assertUpdates(false)   // "do nothing" reports 0 rows updated on a rerun; that's not a failure
                .build();
    }

    @Bean
    ClassifierCompositeItemWriter<SettlementLine> routedLineWriter(JdbcBatchItemWriter<SettlementLine> lineWriter,
                                                                     JdbcBatchItemWriter<SettlementLine> foreignCurrencyLineWriter) {
        return new ClassifierCompositeItemWriterBuilder<SettlementLine>()
                .classifier(line -> "GBP".equals(line.currency()) ? (ItemWriter<SettlementLine>) lineWriter : foreignCurrencyLineWriter)
                .build();
    }
}
