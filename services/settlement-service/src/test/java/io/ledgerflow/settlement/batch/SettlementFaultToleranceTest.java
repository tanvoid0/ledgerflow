package io.ledgerflow.settlement.batch;

import io.ledgerflow.settlement.TestcontainersConfiguration;
import io.ledgerflow.settlement.adapter.out.issuer.FxRateGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.ResourceAccessException;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The fx call is where a transient failure and a bad row both surface, so this is where retry and skip earn
 * their keep: a 0-amount row is rejected without ever being retried, and a flaky fx call is retried until the
 * chunk completes.
 */
@SpringBatchTest
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SettlementFaultToleranceTest {

    private static final LocalDate REJECT_DATE = LocalDate.of(2030, 1, 1);
    private static final LocalDate RETRY_DATE = LocalDate.of(2030, 1, 2);

    @Autowired JobOperatorTestUtils jobs;
    @Autowired @Qualifier("nightlySettlementJob") Job nightlySettlementJob;
    @Autowired JdbcClient db;
    @MockitoBean FxRateGateway fx;

    @BeforeEach
    void useNightlySettlementJob() {
        jobs.setJob(nightlySettlementJob);
    }

    @Test
    void aBadRowIsRejectedNotRetried() throws Exception {
        when(fx.upliftBps(any())).thenReturn(0);
        seedItems(REJECT_DATE, "M-001", 9, 1000L);
        seedItem(REJECT_DATE, "M-001", 0L);

        var execution = jobs.startJob(new JobParametersBuilder().addString("businessDate", REJECT_DATE.toString()).toJobParameters());

        assertThat(execution.getExitStatus().getExitCode()).isEqualTo("COMPLETED");
        assertThat(lineCount(REJECT_DATE)).isEqualTo(9);
        assertThat(rejectCount(REJECT_DATE)).isEqualTo(1);
        assertThat(rejectedItemStatus(REJECT_DATE)).isEqualTo("REJECTED");
    }

    @Test
    void aFlakyFxCallIsRetriedUntilTheChunkCompletes() throws Exception {
        when(fx.upliftBps(eq("GBP")))
                .thenThrow(new ResourceAccessException("down"))
                .thenThrow(new ResourceAccessException("down"))
                .thenReturn(0);
        seedItems(RETRY_DATE, "M-001", 9, 1000L);

        var execution = jobs.startJob(new JobParametersBuilder().addString("businessDate", RETRY_DATE.toString()).toJobParameters());

        assertThat(execution.getExitStatus().getExitCode()).isEqualTo("COMPLETED");
        assertThat(lineCount(RETRY_DATE)).isEqualTo(9);
        // 9 items, each processed once, plus the 2 extra attempts the flaky first item cost the retried chunk.
        verify(fx, times(11)).upliftBps("GBP");
    }

    private void seedItems(LocalDate date, String merchant, int count, long amountMinor) {
        for (int i = 0; i < count; i++) {
            seedItem(date, merchant, amountMinor);
        }
    }

    private void seedItem(LocalDate date, String merchant, long amountMinor) {
        db.sql("""
                insert into settlement_item (payment_id, merchant_id, amount_minor, currency, business_date)
                values (:paymentId, :merchant, :amount, 'GBP', :date)
                """)
                .param("paymentId", UUID.randomUUID()).param("merchant", merchant)
                .param("amount", amountMinor).param("date", date)
                .update();
    }

    private long lineCount(LocalDate date) {
        return db.sql("select count(*) from settlement_line where business_date = :date").param("date", date).query(Long.class).single();
    }

    private long rejectCount(LocalDate date) {
        return db.sql("select count(*) from settlement_reject where business_date = :date").param("date", date).query(Long.class).single();
    }

    private String rejectedItemStatus(LocalDate date) {
        return db.sql("select status from settlement_item where business_date = :date and amount_minor = 0")
                .param("date", date).query(String.class).single();
    }
}
