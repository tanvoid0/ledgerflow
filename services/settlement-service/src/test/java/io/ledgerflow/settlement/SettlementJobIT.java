package io.ledgerflow.settlement;

import io.ledgerflow.settlement.adapter.out.issuer.FxRateGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 40 captures across 2 merchants, netted for one business date; a rerun for the same date is a 409, not a double
 * count. Same seeded data feeds the export (one line per merchant) and the sweep runs against its own untouched rows.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SettlementJobIT {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 9, 1);
    // its own date: the settle job's job instance is keyed on businessDate, and the main test already
    // completes one for BUSINESS_DATE — reusing it here would 409 depending on method order.
    private static final LocalDate EXPORT_BUSINESS_DATE = LocalDate.of(2026, 9, 2);
    private static final String JOB = "nightlySettlementJob";
    private static final String EXPORT_JOB = "statementExportJob";
    private static final String SWEEP_JOB = "agedItemSweepJob";

    @TempDir static Path exportDir;

    @Autowired JdbcClient db;
    @Autowired MockMvcTester mvc;
    @MockitoBean FxRateGateway fx;

    @BeforeEach
    void noUplift() {
        when(fx.upliftBps(any())).thenReturn(0);
    }

    /** The three cron triggers must not fire mid-test; "-" isn't a valid Quartz-style cron so @Scheduled just skips it. */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("settlement.batch.export-dir", () -> exportDir.toString());
        registry.add("settlement.batch.settle-cron", () -> "-");
        registry.add("settlement.batch.export-cron", () -> "-");
        registry.add("settlement.batch.sweep-cron", () -> "-");
    }

    @Test
    void nettedPerMerchantAndIdempotentOnRerun() {
        seedItems();

        var run = mvc.post().uri("/api/v1/batch/jobs/{name}", JOB).contentType(MediaType.APPLICATION_JSON)
                .content("{\"businessDate\":\"%s\"}".formatted(BUSINESS_DATE));
        assertThat(run).hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("COMPLETED");

        assertThat(batchRowCount()).isEqualTo(2);
        assertThat(linesWhereGrossMinusFeeIsNotNet()).isZero();
        assertThat(batchRowsNotMatchingTheirLines()).isZero();
        assertThat(settledItemCount()).isEqualTo(40);

        // the job repository is JDBC-backed against this same Postgres, not the resourceless default
        assertThat(jobInstanceCount()).isEqualTo(1);
        assertThat(stepExecutionCount()).isEqualTo(2);
        assertThat(lineItemsReadCount()).isEqualTo(40);
        assertThat(lineItemsWriteCount()).isEqualTo(40);

        var rerun = mvc.post().uri("/api/v1/batch/jobs/{name}", JOB).contentType(MediaType.APPLICATION_JSON)
                .content("{\"businessDate\":\"%s\"}".formatted(BUSINESS_DATE));
        assertThat(rerun).hasStatus(409);
    }

    @Test
    void exportWritesOneLinePerMerchant() throws IOException {
        seedItems(EXPORT_BUSINESS_DATE);
        var settle = mvc.post().uri("/api/v1/batch/jobs/{name}", JOB).contentType(MediaType.APPLICATION_JSON)
                .content("{\"businessDate\":\"%s\"}".formatted(EXPORT_BUSINESS_DATE));
        assertThat(settle).hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("COMPLETED");

        var run = mvc.post().uri("/api/v1/batch/jobs/{name}", EXPORT_JOB).contentType(MediaType.APPLICATION_JSON)
                .content("{\"businessDate\":\"%s\"}".formatted(EXPORT_BUSINESS_DATE));
        assertThat(run).hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("COMPLETED");

        List<String> lines = Files.readAllLines(exportDir.resolve("settlement-" + EXPORT_BUSINESS_DATE + ".csv"));
        assertThat(lines).hasSize(3);
        assertThat(lines.getFirst()).isEqualTo("merchant_id,currency,gross_minor,fee_minor,net_minor,line_count");
    }

    @Test
    void sweepNowTwiceIsTwoInstancesNotAConflict() {
        var first = mvc.post().uri("/api/v1/batch/jobs/{name}/now", SWEEP_JOB);
        assertThat(first).hasStatus(202).bodyJson().extractingPath("$.status").isEqualTo("COMPLETED");

        var second = mvc.post().uri("/api/v1/batch/jobs/{name}/now", SWEEP_JOB);
        assertThat(second).hasStatus(202).bodyJson().extractingPath("$.status").isEqualTo("COMPLETED");

        assertThat(jobInstanceCountFor(SWEEP_JOB)).isEqualTo(2);
    }

    private void seedItems() {
        seedItems(BUSINESS_DATE);
    }

    private void seedItems(LocalDate date) {
        for (int i = 0; i < 40; i++) {
            var merchant = i % 2 == 0 ? "M-1" : "M-2";
            db.sql("""
                    insert into settlement_item (payment_id, merchant_id, amount_minor, currency, business_date)
                    values (:paymentId, :merchant, :amount, 'GBP', :date)
                    """)
                    .param("paymentId", UUID.randomUUID()).param("merchant", merchant)
                    .param("amount", 1000L + i * 137).param("date", date)
                    .update();
        }
    }

    private long batchRowCount() {
        return db.sql("select count(*) from settlement_batch where business_date = :date")
                .param("date", BUSINESS_DATE).query(Long.class).single();
    }

    private long linesWhereGrossMinusFeeIsNotNet() {
        return db.sql("select count(*) from settlement_line where gross_minor - fee_minor <> net_minor")
                .query(Long.class).single();
    }

    private long batchRowsNotMatchingTheirLines() {
        return db.sql("""
                select count(*) from settlement_batch b join (
                    select merchant_id, sum(gross_minor) g, sum(fee_minor) f, sum(net_minor) n, count(*) c
                      from settlement_line where business_date = :date group by merchant_id
                ) l on l.merchant_id = b.merchant_id and b.business_date = :date
                where b.gross_minor <> l.g or b.fee_minor <> l.f or b.net_minor <> l.n or b.line_count <> l.c
                """).param("date", BUSINESS_DATE).query(Long.class).single();
    }

    private long settledItemCount() {
        return db.sql("select count(*) from settlement_item where business_date = :date and status = 'SETTLED'")
                .param("date", BUSINESS_DATE).query(Long.class).single();
    }

    private long jobInstanceCount() {
        return db.sql("select count(*) from batch_job_instance").query(Long.class).single();
    }

    private long jobInstanceCountFor(String jobName) {
        return db.sql("select count(*) from batch_job_instance where job_name = :name")
                .param("name", jobName).query(Long.class).single();
    }

    private long stepExecutionCount() {
        return db.sql("select count(*) from batch_step_execution").query(Long.class).single();
    }

    private long lineItemsReadCount() {
        return db.sql("select read_count from batch_step_execution where step_name = 'lineItemsStep'").query(Long.class).single();
    }

    private long lineItemsWriteCount() {
        return db.sql("select write_count from batch_step_execution where step_name = 'lineItemsStep'").query(Long.class).single();
    }
}
