package io.ledgerflow.settlement.batch;

import io.ledgerflow.settlement.TestcontainersConfiguration;
import io.ledgerflow.settlement.adapter.out.issuer.FxRateGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The graph end to end: rejects route through rejectReportStep, a clean day skips it, an empty day never nets,
 * month-end adds feeReconciliationStep, and export/notify run side by side. Currency drives which table a line
 * lands in, not which branch of the graph runs.
 */
@SpringBatchTest
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SettlementFlowIT {

    private static final LocalDate CLEAN_DATE = LocalDate.of(2027, 3, 15);
    private static final LocalDate REJECT_DATE = LocalDate.of(2027, 3, 16);
    private static final LocalDate EMPTY_DATE = LocalDate.of(2027, 3, 17);
    private static final LocalDate MONTH_END_DATE = LocalDate.of(2027, 1, 31);
    private static final LocalDate NOT_MONTH_END_DATE = LocalDate.of(2027, 1, 30);
    private static final LocalDate OVERLAP_DATE = LocalDate.of(2027, 3, 18);

    @Autowired JobOperatorTestUtils jobs;
    @Autowired @Qualifier("nightlySettlementJob") Job nightlySettlementJob;
    @Autowired JdbcClient db;
    @MockitoBean FxRateGateway fx;

    @BeforeEach
    void wireJobAndFx() {
        jobs.setJob(nightlySettlementJob);
        when(fx.upliftBps(any())).thenReturn(0);
    }

    @Test
    void cleanDaySkipsRejectReportAndPromotesTheNetTotal() throws Exception {
        seedItems(CLEAN_DATE, 20, "GBP");
        seedItems(CLEAN_DATE, 10, "EUR");

        var execution = jobs.startJob(params(CLEAN_DATE));

        assertThat(execution.getExitStatus().getExitCode()).isEqualTo("COMPLETED");
        assertThat(stepNames(execution)).contains("netByMerchantStep", "statementExportStep", "notifyMerchantsStep")
                .doesNotContain("rejectReportStep", "noopStep", "feeReconciliationStep");
        assertThat(lineItemsExitCode(execution)).isEqualTo("COMPLETED");

        long netTotal = db.sql("select coalesce(sum(net_minor), 0) from settlement_batch where business_date = :date")
                .param("date", CLEAN_DATE).query(Long.class).single();
        assertThat(execution.getExecutionContext().getLong("netTotalMinor")).isEqualTo(netTotal);

        assertThat(lineCount(CLEAN_DATE)).isEqualTo(20);
        assertThat(lineFxCount(CLEAN_DATE)).isEqualTo(10);
    }

    @Test
    void rejectsRouteThroughTheRejectReportStep() throws Exception {
        seedItems(REJECT_DATE, 97, "GBP");
        seedItem(REJECT_DATE, "M-1", 0L, "GBP");
        seedItem(REJECT_DATE, "M-1", 0L, "GBP");
        seedItem(REJECT_DATE, "M-1", 0L, "GBP");

        var execution = jobs.startJob(params(REJECT_DATE));

        assertThat(execution.getExitStatus().getExitCode()).isEqualTo("COMPLETED");
        assertThat(stepNames(execution)).contains("rejectReportStep", "netByMerchantStep", "statementExportStep", "notifyMerchantsStep");
        assertThat(lineItemsExitCode(execution)).isEqualTo("COMPLETED_WITH_REJECTS");
    }

    @Test
    void emptyDayNoopsWithoutNetting() throws Exception {
        var execution = jobs.startJob(params(EMPTY_DATE));

        assertThat(execution.getExitStatus().getExitCode()).isEqualTo("COMPLETED");
        assertThat(stepNames(execution)).contains("noopStep").doesNotContain("netByMerchantStep", "statementExportStep", "notifyMerchantsStep");
        assertThat(lineItemsExitCode(execution)).isEqualTo("NOTHING_TO_DO");
    }

    @Test
    void monthEndRunsFeeReconciliationAndOrdinaryDaysDoNot() throws Exception {
        seedItems(MONTH_END_DATE, 5, "GBP");
        seedItems(NOT_MONTH_END_DATE, 5, "GBP");

        var monthEnd = jobs.startJob(params(MONTH_END_DATE));
        var ordinary = jobs.startJob(params(NOT_MONTH_END_DATE));

        assertThat(stepNames(monthEnd)).contains("feeReconciliationStep");
        assertThat(stepNames(ordinary)).doesNotContain("feeReconciliationStep");
    }

    @Test
    void exportAndNotifyRunSideBySide() throws Exception {
        seedItems(OVERLAP_DATE, 5, "GBP");

        var execution = jobs.startJob(params(OVERLAP_DATE));

        var exportTimes = timesOf(execution, "statementExportStep");
        var notifyTimes = timesOf(execution, "notifyMerchantsStep");
        assertThat(exportTimes[0]).isBefore(notifyTimes[1]);
        assertThat(notifyTimes[0]).isBefore(exportTimes[1]);
    }

    private static org.springframework.batch.core.job.parameters.JobParameters params(LocalDate businessDate) {
        return new JobParametersBuilder().addString("businessDate", businessDate.toString()).toJobParameters();
    }

    private Set<String> stepNames(JobExecution execution) {
        return execution.getStepExecutions().stream().map(StepExecution::getStepName).collect(Collectors.toSet());
    }

    private String lineItemsExitCode(JobExecution execution) {
        return execution.getStepExecutions().stream().filter(s -> s.getStepName().equals("lineItemsStep")).findFirst()
                .orElseThrow(() -> new AssertionError("lineItemsStep did not run")).getExitStatus().getExitCode();
    }

    // no helper returning StepExecution here on purpose: @SpringBatchTest's StepScopeTestExecutionListener treats
    // any test-class method returning StepExecution as a step-scope bean factory, and blows up on real ones
    private java.time.LocalDateTime[] timesOf(JobExecution execution, String name) {
        var step = execution.getStepExecutions().stream().filter(s -> s.getStepName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(name + " did not run"));
        return new java.time.LocalDateTime[] {step.getStartTime(), step.getEndTime()};
    }

    private void seedItems(LocalDate date, int count, String currency) {
        for (int i = 0; i < count; i++) {
            seedItem(date, i % 2 == 0 ? "M-1" : "M-2", 1000L + i, currency);
        }
    }

    private void seedItem(LocalDate date, String merchant, long amountMinor, String currency) {
        db.sql("""
                insert into settlement_item (payment_id, merchant_id, amount_minor, currency, business_date)
                values (:paymentId, :merchant, :amount, :currency, :date)
                """)
                .param("paymentId", UUID.randomUUID()).param("merchant", merchant)
                .param("amount", amountMinor).param("currency", currency).param("date", date)
                .update();
    }

    private long lineCount(LocalDate date) {
        return db.sql("select count(*) from settlement_line where business_date = :date").param("date", date).query(Long.class).single();
    }

    private long lineFxCount(LocalDate date) {
        return db.sql("select count(*) from settlement_line_fx where business_date = :date").param("date", date).query(Long.class).single();
    }
}
