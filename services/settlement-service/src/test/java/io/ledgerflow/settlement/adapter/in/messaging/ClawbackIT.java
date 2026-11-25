package io.ledgerflow.settlement.adapter.in.messaging;

import io.ledgerflow.events.EventEnvelope;
import io.ledgerflow.events.settlement.RevokeCaptures;
import io.ledgerflow.settlement.TestcontainersConfiguration;
import io.ledgerflow.settlement.adapter.out.account.AccountClient;
import io.ledgerflow.settlement.adapter.out.account.AccountClient.AccountView;
import io.ledgerflow.settlement.adapter.out.account.AccountClient.TransferRequest;
import io.ledgerflow.settlement.adapter.out.account.AccountClient.TransferResponse;
import io.ledgerflow.settlement.adapter.out.account.AccountClient.WalletView;
import io.ledgerflow.settlement.adapter.out.issuer.FxRateGateway;
import io.ledgerflow.settlement.config.SettlementProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A refund after the organiser was paid pulls the item's net back out of the organiser's wallet, once. */
@SpringBootTest(properties = "settlement.batch.export-dir=target/statements")
@AutoConfigureMockMvc
@WithMockUser(roles = "ledger-write")
@Import(TestcontainersConfiguration.class)
class ClawbackIT {

    @Autowired CaptureCommandListener listener;
    @Autowired JdbcClient db;
    @Autowired JsonMapper json;
    @Autowired SettlementProperties props;
    @Autowired MockMvcTester mvc;
    @MockitoBean AccountClient account;
    @MockitoBean FxRateGateway fx;

    @Test
    void settledItemIsClawedBackFromTheOrganiserOnce() {
        var paymentId = UUID.randomUUID();
        var organiser = UUID.nameUUIDFromBytes("organiser:org-clawback".getBytes(StandardCharsets.UTF_8));
        var organiserWallet = UUID.randomUUID();
        when(account.account(organiser)).thenReturn(new AccountView(organiser, List.of(new WalletView(organiserWallet, "MAIN"))));
        when(account.transfer(any(), any())).thenReturn(new TransferResponse(UUID.randomUUID()));
        long itemId = db.sql("""
                INSERT INTO settlement_item (payment_id, merchant_id, amount_minor, currency, business_date, status)
                VALUES (:p, 'org-clawback', 2800, 'GBP', DATE '2027-04-01', 'SETTLED') RETURNING id
                """).param("p", paymentId).query(Long.class).single();
        db.sql("INSERT INTO settlement_line VALUES (:id, DATE '2027-04-01', 'org-clawback', 'GBP', 2800, 111, 2689)")
                .param("id", itemId).update();

        revoke(paymentId);
        revoke(paymentId);

        verify(account).transfer(eq("clawback-" + itemId),
                eq(new TransferRequest(organiserWallet, props.settlementWalletId(), 2689, "GBP", "clawback of payout for payment " + paymentId)));
        assertThat(db.sql("SELECT status FROM settlement_item WHERE id = :id").param("id", itemId).query(String.class).single())
                .isEqualTo("CLAWED_BACK");
    }

    @Test
    void foreignCurrencyItemIsClawedBackInItsOwnCurrency() {
        var paymentId = UUID.randomUUID();
        var organiser = UUID.nameUUIDFromBytes("organiser:org-fx".getBytes(StandardCharsets.UTF_8));
        var organiserWallet = UUID.randomUUID();
        when(account.account(organiser)).thenReturn(new AccountView(organiser, List.of(new WalletView(organiserWallet, "MAIN"))));
        when(account.transfer(any(), any())).thenReturn(new TransferResponse(UUID.randomUUID()));
        long itemId = db.sql("""
                INSERT INTO settlement_item (payment_id, merchant_id, amount_minor, currency, business_date, status)
                VALUES (:p, 'org-fx', 2800, 'EUR', DATE '2027-07-01', 'SETTLED') RETURNING id
                """).param("p", paymentId).query(Long.class).single();
        db.sql("INSERT INTO settlement_line_fx VALUES (:id, DATE '2027-07-01', 'org-fx', 'EUR', 2800, 125, 2675)")
                .param("id", itemId).update();

        revoke(paymentId);

        verify(account).transfer(eq("clawback-" + itemId),
                eq(new TransferRequest(organiserWallet, props.settlementWalletId(), 2675, "EUR", "clawback of payout for payment " + paymentId)));
        assertThat(db.sql("SELECT status FROM settlement_item WHERE id = :id").param("id", itemId).query(String.class).single())
                .isEqualTo("CLAWED_BACK");
    }

    @Test
    void unpaidItemIsNotClawedBack() {
        var paymentId = UUID.randomUUID();
        db.sql("""
                INSERT INTO settlement_item (payment_id, merchant_id, amount_minor, currency, business_date, status)
                VALUES (:p, 'org-clawback', 2800, 'GBP', DATE '2027-04-02', 'NEW')
                """).param("p", paymentId).update();

        revoke(paymentId);

        verify(account, never()).transfer(startsWith("clawback-"), any());
        assertThat(db.sql("SELECT status FROM settlement_item WHERE payment_id = :p").param("p", paymentId).query(String.class).single())
                .isEqualTo("REVOKED");
    }

    @Test
    void shortWalletCarriesTheDebtIntoLaterBatches() {
        var paymentId = UUID.randomUUID();
        var organiser = UUID.nameUUIDFromBytes("organiser:org-short".getBytes(StandardCharsets.UTF_8));
        when(account.account(organiser)).thenReturn(new AccountView(organiser, List.of(new WalletView(UUID.randomUUID(), "MAIN"))));
        when(account.transfer(any(), any())).thenThrow(HttpClientErrorException.create(
                HttpStatus.UNPROCESSABLE_CONTENT, "insufficient funds", HttpHeaders.EMPTY, new byte[0], null));
        long itemId = db.sql("""
                INSERT INTO settlement_item (payment_id, merchant_id, amount_minor, currency, business_date, status)
                VALUES (:p, 'org-short', 2800, 'GBP', DATE '2027-05-01', 'SETTLED') RETURNING id
                """).param("p", paymentId).query(Long.class).single();
        db.sql("INSERT INTO settlement_line VALUES (:id, DATE '2027-05-01', 'org-short', 'GBP', 2800, 111, 2689)")
                .param("id", itemId).update();

        revoke(paymentId);
        revoke(paymentId);

        // 2689 owed: the first day only nets 941, the other 1748 comes off the next
        assertThat(netFor("2027-05-02", 1000)).isEqualTo(941 - 2689);
        assertThat(netFor("2027-05-03", 5000)).isEqualTo(4825 - 1748);
    }

    @Test
    void concurrentRevokesClawBackOnce() throws Exception {
        var paymentId = UUID.randomUUID();
        var organiser = UUID.nameUUIDFromBytes("organiser:org-race".getBytes(StandardCharsets.UTF_8));
        when(account.account(organiser)).thenReturn(new AccountView(organiser, List.of(new WalletView(UUID.randomUUID(), "MAIN"))));
        long itemId = db.sql("""
                INSERT INTO settlement_item (payment_id, merchant_id, amount_minor, currency, business_date, status)
                VALUES (:p, 'org-race', 2800, 'GBP', DATE '2027-06-01', 'SETTLED') RETURNING id
                """).param("p", paymentId).query(Long.class).single();
        db.sql("INSERT INTO settlement_line VALUES (:id, DATE '2027-06-01', 'org-race', 'GBP', 2800, 111, 2689)")
                .param("id", itemId).update();
        var second = new FutureTask<Void>(() -> revoke(paymentId), null);
        // while the first holds the item a second revoke arrives and must wait; then the wallet turns out short
        when(account.transfer(any(), any())).thenAnswer(call -> {
            new Thread(second).start();
            assertThatThrownBy(() -> second.get(2, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
            throw HttpClientErrorException.create(
                    HttpStatus.UNPROCESSABLE_CONTENT, "insufficient funds", HttpHeaders.EMPTY, new byte[0], null);
        }).thenReturn(new TransferResponse(UUID.randomUUID()));

        revoke(paymentId);
        second.get(10, TimeUnit.SECONDS);

        verify(account).transfer(eq("clawback-" + itemId), any());
        assertThat(db.sql("SELECT status FROM settlement_item WHERE id = :id").param("id", itemId).query(String.class).single())
                .isEqualTo("CARRIED");
    }

    private long netFor(String date, long gross) {
        db.sql("""
                INSERT INTO settlement_item (payment_id, merchant_id, amount_minor, currency, business_date, status)
                VALUES (:p, 'org-short', :gross, 'GBP', CAST(:date AS date), 'NEW')
                """).param("p", UUID.randomUUID()).param("gross", gross).param("date", date).update();
        assertThat(mvc.post().uri("/api/v1/batch/jobs/nightlySettlementJob").contentType(MediaType.APPLICATION_JSON)
                .content("{\"businessDate\":\"%s\"}".formatted(date)))
                .hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("COMPLETED");
        return db.sql("SELECT net_minor FROM settlement_batch WHERE merchant_id = 'org-short' AND business_date = CAST(:date AS date)")
                .param("date", date).query(Long.class).single();
    }

    private void revoke(UUID paymentId) {
        EventEnvelope<tools.jackson.databind.JsonNode> cmd = EventEnvelope.of(RevokeCaptures.TYPE, paymentId, 1, "test", null, json.valueToTree(new RevokeCaptures(paymentId)));
        listener.onCommand(cmd, mock(Acknowledgment.class));
    }
}
