package io.ledgerflow.settlement;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 40 captures across 2 merchants, netted for one business date; a rerun for the same date is a 409, not a double count. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SettlementJobIT {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 9, 1);
    private static final String JOB = "nightlySettlementJob";

    @Autowired JdbcClient db;
    @Autowired MockMvcTester mvc;

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

    private void seedItems() {
        for (int i = 0; i < 40; i++) {
            var merchant = i % 2 == 0 ? "M-1" : "M-2";
            db.sql("""
                    insert into settlement_item (payment_id, merchant_id, amount_minor, currency, business_date)
                    values (:paymentId, :merchant, :amount, 'GBP', :date)
                    """)
                    .param("paymentId", UUID.randomUUID()).param("merchant", merchant)
                    .param("amount", 1000L + i * 137).param("date", BUSINESS_DATE)
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
