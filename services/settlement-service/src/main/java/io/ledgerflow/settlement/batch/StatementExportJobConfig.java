package io.ledgerflow.settlement.batch;

import io.ledgerflow.settlement.config.BatchProperties;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.database.JdbcCursorItemReader;
import org.springframework.batch.infrastructure.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.batch.infrastructure.item.file.FlatFileItemWriter;
import org.springframework.batch.infrastructure.item.file.builder.FlatFileItemWriterBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.LocalDate;

/** One CSV per business date: the day's settlement_batch rows, merchant by merchant. Read-only; nothing here mutates a table. */
@Configuration
class StatementExportJobConfig {

    /** One settlement_batch row, as it comes off the netting step — not SettlementLine, this is per merchant, not per item. */
    record SettlementBatchRow(String merchantId, String currency, long grossMinor, long feeMinor, long netMinor, int lineCount) {}

    @Bean
    @StepScope
    JdbcCursorItemReader<SettlementBatchRow> batchRowReader(@Value("#{jobParameters['businessDate']}") String businessDate, DataSource ds) {
        return new JdbcCursorItemReaderBuilder<SettlementBatchRow>()
                .name("settlementBatchRowReader")
                .dataSource(ds)
                .sql("select merchant_id, currency, gross_minor, fee_minor, net_minor, line_count "
                        + "from settlement_batch where business_date = ? order by merchant_id")
                .queryArguments(LocalDate.parse(businessDate))
                .rowMapper((rs, rowNum) -> new SettlementBatchRow(rs.getString("merchant_id"), rs.getString("currency"),
                        rs.getLong("gross_minor"), rs.getLong("fee_minor"), rs.getLong("net_minor"), rs.getInt("line_count")))
                .build();
    }

    @Bean
    @StepScope
    FlatFileItemWriter<SettlementBatchRow> statementWriter(@Value("#{jobParameters['businessDate']}") String businessDate,
                                                            BatchProperties props) {
        return new FlatFileItemWriterBuilder<SettlementBatchRow>()
                .name("statementWriter")
                .resource(new FileSystemResource(props.exportDir() + "/settlement-" + businessDate + ".csv"))
                .shouldDeleteIfExists(true)
                .lineAggregator(row -> "%s,%s,%d,%d,%d,%d".formatted(
                        row.merchantId(), row.currency(), row.grossMinor(), row.feeMinor(), row.netMinor(), row.lineCount()))
                .headerCallback(writer -> writer.write("merchant_id,currency,gross_minor,fee_minor,net_minor,line_count"))
                .build();
    }

    @Bean
    Step statementExportStep(JobRepository jobRepository, PlatformTransactionManager tx,
                              JdbcCursorItemReader<SettlementBatchRow> batchRowReader,
                              FlatFileItemWriter<SettlementBatchRow> statementWriter) {
        return new StepBuilder("statementExportStep", jobRepository)
                .<SettlementBatchRow, SettlementBatchRow>chunk(50)
                .transactionManager(tx)
                .reader(batchRowReader)
                .writer(statementWriter)
                .build();
    }

    @Bean
    Job statementExportJob(JobRepository jobRepository, Step statementExportStep) {
        return new JobBuilder("statementExportJob", jobRepository)
                .start(statementExportStep)
                .build();
    }
}
