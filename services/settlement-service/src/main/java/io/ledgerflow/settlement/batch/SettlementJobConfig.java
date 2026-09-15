package io.ledgerflow.settlement.batch;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.database.JdbcBatchItemWriter;
import org.springframework.batch.infrastructure.item.database.JdbcCursorItemReader;
import org.springframework.batch.infrastructure.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.infrastructure.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.sql.Date;
import java.time.LocalDate;
import java.util.UUID;

/** The nightly job: settlement_item -> settlement_line (fee taken out) -> settlement_batch (netted per merchant). */
@Configuration
class SettlementJobConfig {

    @Bean
    @StepScope
    JdbcCursorItemReader<SettlementItem> itemReader(@Value("#{jobParameters['businessDate']}") String businessDate, DataSource ds) {
        return new JdbcCursorItemReaderBuilder<SettlementItem>()
                .name("settlementItemReader")
                .dataSource(ds)
                .sql("select id, payment_id, merchant_id, amount_minor, currency, business_date "
                        + "from settlement_item where business_date = ? and status = 'NEW' order by id")
                .queryArguments(LocalDate.parse(businessDate))   // bound as DATE; the raw string left unparsed compares varchar = date and Postgres refuses it

                .rowMapper((rs, rowNum) -> new SettlementItem(rs.getLong("id"), UUID.fromString(rs.getString("payment_id")),
                        rs.getString("merchant_id"), rs.getLong("amount_minor"), rs.getString("currency"),
                        rs.getDate("business_date").toLocalDate()))
                .build();
    }

    @Bean
    ItemProcessor<SettlementItem, SettlementLine> lineProcessor() {
        return item -> {
            long gross = item.amountMinor();
            long fee = fee(gross);
            return new SettlementLine(item.id(), item.businessDate(), item.merchantId(), item.currency(), gross, fee, gross - fee);
        };
    }

    @Bean
    JdbcBatchItemWriter<SettlementLine> lineWriter(DataSource ds) {
        return new JdbcBatchItemWriterBuilder<SettlementLine>()
                .dataSource(ds)
                .sql("""
                        insert into settlement_line (item_id, business_date, merchant_id, currency, gross_minor, fee_minor, net_minor)
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
    Step lineItemsStep(JobRepository jobRepository, PlatformTransactionManager tx, JdbcCursorItemReader<SettlementItem> itemReader,
                       ItemProcessor<SettlementItem, SettlementLine> lineProcessor, JdbcBatchItemWriter<SettlementLine> lineWriter) {
        return new StepBuilder("lineItemsStep", jobRepository)
                .<SettlementItem, SettlementLine>chunk(100)
                .transactionManager(tx)
                .reader(itemReader)
                .processor(lineProcessor)
                .writer(lineWriter)
                .build();
    }

    /**
     * One transaction: net the day's lines into settlement_batch, then mark their items SETTLED. Both statements
     * are idempotent (upsert, and a status filter that only matches NEW), so if the JVM dies between them, step
     * 24 reruns the whole tasklet and lands in the same place.
     */
    @Bean
    @StepScope
    Tasklet netByMerchantTasklet(@Value("#{jobParameters['businessDate']}") String businessDate, JdbcClient db) {
        return (contribution, chunkContext) -> {
            LocalDate date = LocalDate.parse(businessDate);
            db.sql("""
                    insert into settlement_batch (business_date, merchant_id, currency, gross_minor, fee_minor, net_minor, line_count)
                    select business_date, merchant_id, currency, sum(gross_minor), sum(fee_minor), sum(net_minor), count(*)
                      from settlement_line where business_date = :date group by 1, 2, 3
                    on conflict (business_date, merchant_id) do update set
                        gross_minor = excluded.gross_minor, fee_minor = excluded.fee_minor,
                        net_minor = excluded.net_minor, line_count = excluded.line_count
                    """).param("date", date).update();
            db.sql("update settlement_item set status = 'SETTLED' where business_date = :date and status = 'NEW'")
                    .param("date", date).update();
            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    Step netByMerchantStep(JobRepository jobRepository, PlatformTransactionManager tx, Tasklet netByMerchantTasklet) {
        return new StepBuilder("netByMerchantStep", jobRepository)
                .tasklet(netByMerchantTasklet)
                .transactionManager(tx)
                .build();
    }

    @Bean
    Job nightlySettlementJob(JobRepository jobRepository, Step lineItemsStep, Step netByMerchantStep) {
        return new JobBuilder("nightlySettlementJob", jobRepository)
                .start(lineItemsStep)
                .next(netByMerchantStep)
                .build();
    }

    static long fee(long grossMinor) {
        return grossMinor * 29 / 1000 + 30;   // 2.9% + 30, integer arithmetic throughout
    }
}
