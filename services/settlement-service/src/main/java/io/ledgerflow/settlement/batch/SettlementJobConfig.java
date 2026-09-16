package io.ledgerflow.settlement.batch;

import io.ledgerflow.settlement.adapter.out.issuer.FxRateGateway;
import io.ledgerflow.settlement.config.LineItemsProperties;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.partition.Partitioner;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.batch.infrastructure.item.database.JdbcBatchItemWriter;
import org.springframework.batch.infrastructure.item.database.JdbcCursorItemReader;
import org.springframework.batch.infrastructure.item.database.JdbcPagingItemReader;
import org.springframework.batch.infrastructure.item.database.Order;
import org.springframework.batch.infrastructure.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.infrastructure.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.batch.infrastructure.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.batch.infrastructure.item.database.support.PostgresPagingQueryProvider;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClientException;

import javax.sql.DataSource;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
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
                .rowMapper(SettlementJobConfig::mapItem)
                .build();
    }

    /** threads-paging's reader: same rows as itemReader, but a paging query so a race across 4 threads doesn't share one cursor. */
    @Bean
    @StepScope
    JdbcPagingItemReader<SettlementItem> pagingItemReader(@Value("#{jobParameters['businessDate']}") String businessDate, DataSource ds)
            throws Exception {
        var queryProvider = new PostgresPagingQueryProvider();
        queryProvider.setSelectClause("id, payment_id, merchant_id, amount_minor, currency, business_date");
        queryProvider.setFromClause("settlement_item");
        queryProvider.setWhereClause("status = 'NEW' and business_date = :businessDate");
        queryProvider.setSortKeys(Map.of("id", Order.ASCENDING));

        return new JdbcPagingItemReaderBuilder<SettlementItem>()
                .name("settlementPagingItemReader")
                .dataSource(ds)
                .queryProvider(queryProvider)
                .parameterValues(Map.of("businessDate", LocalDate.parse(businessDate)))
                .pageSize(100)
                .saveState(false)
                .rowMapper(SettlementJobConfig::mapItem)
                .build();
    }

    /** partition mode's reader: one contiguous id range per worker, so saveState is honest — each worker owns its own slice. */
    @Bean
    @StepScope
    JdbcPagingItemReader<SettlementItem> partitionedItemReader(@Value("#{jobParameters['businessDate']}") String businessDate,
                                                                 @Value("#{stepExecutionContext['minId']}") Long minId,
                                                                 @Value("#{stepExecutionContext['maxId']}") Long maxId, DataSource ds)
            throws Exception {
        var queryProvider = new PostgresPagingQueryProvider();
        queryProvider.setSelectClause("id, payment_id, merchant_id, amount_minor, currency, business_date");
        queryProvider.setFromClause("settlement_item");
        queryProvider.setWhereClause("status = 'NEW' and business_date = :businessDate and id between :minId and :maxId");
        queryProvider.setSortKeys(Map.of("id", Order.ASCENDING));

        return new JdbcPagingItemReaderBuilder<SettlementItem>()
                .name("partitionedItemReader")
                .dataSource(ds)
                .queryProvider(queryProvider)
                .parameterValues(Map.of("businessDate", LocalDate.parse(businessDate), "minId", minId, "maxId", maxId))
                .pageSize(100)
                .saveState(true)
                .rowMapper(SettlementJobConfig::mapItem)
                .build();
    }

    @Bean
    @StepScope
    Partitioner idRangePartitioner(@Value("#{jobParameters['businessDate']}") String businessDate, JdbcClient db, LineItemsProperties props) {
        return new IdRangePartitioner(db, LocalDate.parse(businessDate), props.gridSize());
    }

    /** threads-cursor and threads-paging share this pool; partition mode also uses it, sized to the grid instead of a flat 4. */
    @Bean
    SimpleAsyncTaskExecutor settleExecutor(LineItemsProperties props) {
        var executor = new SimpleAsyncTaskExecutor("settle-");
        executor.setVirtualThreads(true);
        executor.setConcurrencyLimit(props.lineItems() == LineItemsProperties.Mode.PARTITION ? props.gridSize() : 4);
        return executor;
    }

    private static SettlementItem mapItem(ResultSet rs, int rowNum) throws SQLException {
        return new SettlementItem(rs.getLong("id"), UUID.fromString(rs.getString("payment_id")),
                rs.getString("merchant_id"), rs.getLong("amount_minor"), rs.getString("currency"),
                rs.getDate("business_date").toLocalDate());
    }

    @Bean
    ItemProcessor<SettlementItem, SettlementLine> lineProcessor(FxRateGateway fx) {
        return item -> {
            long gross = item.amountMinor();
            if (gross <= 0) {
                throw new UnsettleableItemException("item " + item.id() + " has amount " + gross);
            }
            long uplift = gross * fx.upliftBps(item.currency()) / 10_000;
            long fee = fee(gross) + uplift;
            return new SettlementLine(item.id(), item.businessDate(), item.merchantId(), item.currency(), gross, fee, gross - fee);
        };
    }

    /** Transient-only: an fx call that timed out or 5xx'd is worth retrying, a bad row (see UnsettleableItemException) is not. */
    @Bean
    RetryPolicy transientOnly() {
        return RetryPolicy.builder()
                .maxRetries(4)
                .delay(Duration.ofMillis(200))
                .multiplier(2.0)
                .maxDelay(Duration.ofSeconds(5))
                .jitter(Duration.ofMillis(100))
                .includes(RestClientException.class)
                .excludes(UnsettleableItemException.class)
                .build();
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

    /** Shared by every mode: reader is the only thing that varies, executor only for the threaded ones. */
    private static Step chunkStep(String name, JobRepository jobRepository, PlatformTransactionManager tx,
                                   ItemReader<SettlementItem> reader, ItemProcessor<SettlementItem, SettlementLine> lineProcessor,
                                   JdbcBatchItemWriter<SettlementLine> lineWriter, RetryPolicy transientOnly,
                                   RejectUnsettleableItems rejectUnsettleableItems, SettlementMetrics metrics,
                                   SimpleAsyncTaskExecutor executor) {
        var builder = new StepBuilder(name, jobRepository)
                .<SettlementItem, SettlementLine>chunk(100)
                .transactionManager(tx)
                .reader(reader)
                .processor(lineProcessor)
                .writer(lineWriter)
                .faultTolerant()
                .retryPolicy(transientOnly)
                .skipPolicy(rejectUnsettleableItems)
                .skipListener(rejectUnsettleableItems)
                .listener((StepExecutionListener) metrics);
        if (executor != null) {
            builder.taskExecutor(executor);
        }
        return builder.build();
    }

    @Bean
    Step lineItemsWorkerStep(JobRepository jobRepository, PlatformTransactionManager tx,
                             JdbcPagingItemReader<SettlementItem> partitionedItemReader,
                             ItemProcessor<SettlementItem, SettlementLine> lineProcessor, JdbcBatchItemWriter<SettlementLine> lineWriter,
                             RetryPolicy transientOnly, RejectUnsettleableItems rejectUnsettleableItems, SettlementMetrics metrics) {
        return chunkStep("lineItemsWorkerStep", jobRepository, tx, partitionedItemReader, lineProcessor, lineWriter, transientOnly,
                rejectUnsettleableItems, metrics, null);
    }

    @Bean
    Step lineItemsStep(JobRepository jobRepository, PlatformTransactionManager tx, LineItemsProperties props,
                       JdbcCursorItemReader<SettlementItem> itemReader, JdbcPagingItemReader<SettlementItem> pagingItemReader,
                       ItemProcessor<SettlementItem, SettlementLine> lineProcessor, JdbcBatchItemWriter<SettlementLine> lineWriter,
                       RetryPolicy transientOnly, RejectUnsettleableItems rejectUnsettleableItems, SettlementMetrics metrics,
                       SimpleAsyncTaskExecutor settleExecutor, Partitioner idRangePartitioner, Step lineItemsWorkerStep) {
        return switch (props.lineItems()) {
            case SINGLE -> chunkStep("lineItemsStep", jobRepository, tx, itemReader, lineProcessor, lineWriter, transientOnly,
                    rejectUnsettleableItems, metrics, null);
            case THREADS_CURSOR -> chunkStep("lineItemsStep", jobRepository, tx, itemReader, lineProcessor, lineWriter, transientOnly,
                    rejectUnsettleableItems, metrics, settleExecutor);
            case THREADS_PAGING -> chunkStep("lineItemsStep", jobRepository, tx, pagingItemReader, lineProcessor, lineWriter, transientOnly,
                    rejectUnsettleableItems, metrics, settleExecutor);
            case PARTITION -> new StepBuilder("lineItemsStep", jobRepository)
                    .partitioner("lineItemsWorkerStep", idRangePartitioner)
                    .step(lineItemsWorkerStep)
                    .gridSize(props.gridSize())
                    .taskExecutor(settleExecutor)
                    .build();
        };
    }

    /**
     * One transaction: net the day's lines into settlement_batch, then mark their items SETTLED. Both statements
     * are idempotent (upsert, and a status filter that only matches NEW), so if the JVM dies between them, step
     * 24 reruns the whole tasklet and lands in the same place. The second statement also requires a line to
     * exist: a rejected row must not come out SETTLED.
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
            db.sql("""
                    update settlement_item set status = 'SETTLED' where business_date = :date and status = 'NEW'
                      and exists (select 1 from settlement_line l where l.item_id = settlement_item.id)
                    """).param("date", date).update();
            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    Step netByMerchantStep(JobRepository jobRepository, PlatformTransactionManager tx, Tasklet netByMerchantTasklet,
                           SettlementMetrics metrics) {
        return new StepBuilder("netByMerchantStep", jobRepository)
                .tasklet(netByMerchantTasklet)
                .transactionManager(tx)
                .listener((StepExecutionListener) metrics)
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
