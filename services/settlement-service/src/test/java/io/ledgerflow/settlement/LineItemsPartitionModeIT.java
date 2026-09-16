package io.ledgerflow.settlement;

import io.ledgerflow.settlement.adapter.out.issuer.FxRateGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** 1,000 items, grid-size 4: every partition owns a contiguous id range with its own paging state, and every item settles exactly once. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"ledgerflow.settlement.line-items=partition", "ledgerflow.settlement.grid-size=4"})
class LineItemsPartitionModeIT {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 9, 3);
    private static final String JOB = "nightlySettlementJob";

    @Autowired JdbcClient db;
    @Autowired MockMvcTester mvc;
    @MockitoBean FxRateGateway fx;

    @BeforeEach
    void noUplift() {
        when(fx.upliftBps(any())).thenReturn(0);
    }

    @Test
    void fourWorkerPartitionsSettleEveryItemExactlyOnce() {
        for (int i = 0; i < 1000; i++) {
            var merchant = i % 2 == 0 ? "M-1" : "M-2";
            db.sql("""
                    insert into settlement_item (payment_id, merchant_id, amount_minor, currency, business_date)
                    values (:paymentId, :merchant, :amount, 'GBP', :date)
                    """)
                    .param("paymentId", UUID.randomUUID()).param("merchant", merchant)
                    .param("amount", 1000L + i).param("date", BUSINESS_DATE)
                    .update();
        }

        var run = mvc.post().uri("/api/v1/batch/jobs/{name}", JOB).contentType(MediaType.APPLICATION_JSON)
                .content("{\"businessDate\":\"%s\"}".formatted(BUSINESS_DATE));
        assertThat(run).hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("COMPLETED");

        assertThat(lineCount()).isEqualTo(1000);
        assertThat(settledItemCount()).isEqualTo(1000);
        assertThat(workerStepExecutionCount()).isEqualTo(4);
    }

    private long lineCount() {
        return db.sql("select count(*) from settlement_line where business_date = :date")
                .param("date", BUSINESS_DATE).query(Long.class).single();
    }

    private long settledItemCount() {
        return db.sql("select count(*) from settlement_item where business_date = :date and status = 'SETTLED'")
                .param("date", BUSINESS_DATE).query(Long.class).single();
    }

    private long workerStepExecutionCount() {
        return db.sql("select count(*) from batch_step_execution where step_name like 'lineItemsWorkerStep:partition%'")
                .query(Long.class).single();
    }
}
