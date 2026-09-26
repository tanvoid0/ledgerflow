package io.ledgerflow.settlement;

import io.ledgerflow.settlement.adapter.out.issuer.FxRateGateway;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * One JVM wearing both the manager and worker hats (the dev shape): requests and replies still cross the
 * broker, there is just no second pod. Flyway is forced back on — the worker profile alone turns it off, but
 * this JVM is also the manager, and something has to migrate.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles({"manager", "worker"})
@TestPropertySource(properties = {"ledgerflow.settlement.line-items=remote", "ledgerflow.settlement.grid-size=4",
        "spring.flyway.enabled=true"})
// this class's containers, consumer groups and listener containers are unlike every other IT's; caching this
// context alongside the others for the rest of the module's run is what starved a later class's Redpanda
// container of resources (exit 139) — close it, and its containers, the moment this class is done
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@WithMockUser
class RemoteWorkersIT {

    private static final LocalDate PARTITION_DATE = LocalDate.of(2026, 9, 20);
    private static final LocalDate CHUNKING_DATE = LocalDate.of(2026, 9, 21);
    private static final String JOB = "nightlySettlementJob";
    private static final String CHUNKING_JOB = "lineItemsChunkingJob";

    @TestConfiguration(proxyBeanMethods = false)
    static class Topics {
        @Bean
        NewTopic partitionRequestsTopic() {
            return new NewTopic("ledgerflow.settlement.partition-requests.v1", 8, (short) 1);
        }

        @Bean
        NewTopic chunkRequestsTopic() {
            return new NewTopic("ledgerflow.settlement.chunk-requests.v1", 3, (short) 1);
        }

        @Bean
        NewTopic chunkRepliesTopic() {
            return new NewTopic("ledgerflow.settlement.chunk-replies.v1", 3, (short) 1);
        }
    }

    @Autowired JdbcClient db;
    @Autowired MockMvcTester mvc;
    @MockitoBean FxRateGateway fx;

    @BeforeEach
    void noUplift() {
        when(fx.upliftBps(any())).thenReturn(0);
    }

    @Test
    void gridSizeFourWorkersOverTheWireSettleEveryItemExactlyOnce() {
        seedItems(PARTITION_DATE, 700, "GBP");
        seedItems(PARTITION_DATE, 300, "EUR");

        var run = mvc.post().uri("/api/v1/batch/jobs/{name}", JOB).contentType(MediaType.APPLICATION_JSON)
                .content("{\"businessDate\":\"%s\"}".formatted(PARTITION_DATE));
        assertThat(run).hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("COMPLETED");

        // no in-JVM path in remote mode: COMPLETED here only happens if every request crossed the broker and came back
        assertThat(completedWorkerPartitionCount()).isEqualTo(4);
        assertThat(lineCount(PARTITION_DATE) + lineFxCount(PARTITION_DATE)).isEqualTo(1000);
        assertThat(settledItemCount(PARTITION_DATE)).isEqualTo(1000);
    }

    @Test
    void chunkingJobIsListedAndSettlesEveryItem() {
        assertThat(mvc.get().uri("/api/v1/batch/jobs")).hasStatusOk().bodyText().contains(CHUNKING_JOB);

        seedItems(CHUNKING_DATE, 1000, "GBP");

        var run = mvc.post().uri("/api/v1/batch/jobs/{name}", CHUNKING_JOB).contentType(MediaType.APPLICATION_JSON)
                .content("{\"businessDate\":\"%s\"}".formatted(CHUNKING_DATE));
        assertThat(run).hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("COMPLETED");

        assertThat(lineCount(CHUNKING_DATE)).isEqualTo(1000);
    }

    private void seedItems(LocalDate date, int count, String currency) {
        for (int i = 0; i < count; i++) {
            var merchant = i % 2 == 0 ? "M-1" : "M-2";
            db.sql("""
                    insert into settlement_item (payment_id, merchant_id, amount_minor, currency, business_date)
                    values (:paymentId, :merchant, :amount, :currency, :date)
                    """)
                    .param("paymentId", UUID.randomUUID()).param("merchant", merchant)
                    .param("amount", 1000L + i).param("currency", currency).param("date", date)
                    .update();
        }
    }

    private long completedWorkerPartitionCount() {
        return db.sql("""
                select count(*) from batch_step_execution
                 where step_name like 'lineItemsWorkerStep:partition%' and status = 'COMPLETED'
                """).query(Long.class).single();
    }

    private long lineCount(LocalDate date) {
        return db.sql("select count(*) from settlement_line where business_date = :date").param("date", date).query(Long.class).single();
    }

    private long lineFxCount(LocalDate date) {
        return db.sql("select count(*) from settlement_line_fx where business_date = :date").param("date", date).query(Long.class).single();
    }

    private long settledItemCount(LocalDate date) {
        return db.sql("select count(*) from settlement_item where business_date = :date and status = 'SETTLED'")
                .param("date", date).query(Long.class).single();
    }
}
