package io.ledgerflow.settlement.batch;

import io.ledgerflow.events.EventEnvelope;
import io.ledgerflow.events.settlement.MerchantSettled;
import io.ledgerflow.starter.messaging.OutboxAppender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

/** Last step of the nightly job: one MerchantSettled per netted merchant, so notification-service can tell them. */
@Configuration
@Slf4j
class NotifyMerchantsConfig {

    @Bean
    @StepScope
    Tasklet notifyMerchantsTasklet(@Value("#{jobParameters['businessDate']}") String businessDate,
                                    @Value("#{jobExecutionContext['netTotalMinor']}") Long netTotalMinor,
                                    @Value("#{jobExecutionContext['merchantCount']}") Long merchantCount,
                                    JdbcClient db, OutboxAppender outbox) {
        return (contribution, chunkContext) -> {
            LocalDate date = LocalDate.parse(businessDate);
            long total = netTotalMinor == null ? 0 : netTotalMinor;
            if (netTotalMinor == null) {
                log.info("notifyMerchantsTasklet: no promoted netTotalMinor for {}, treating as 0", date);
            }
            var rows = db.sql("select merchant_id, currency, net_minor from settlement_batch where business_date = :d order by merchant_id")
                    .param("d", date)
                    .query((rs, rowNum) -> new Object[]{rs.getString("merchant_id"), rs.getString("currency"), rs.getLong("net_minor")})
                    .list();
            for (var row : rows) {
                String merchantId = (String) row[0];
                String currency = (String) row[1];
                long netMinor = (Long) row[2];
                var aggregateId = UUID.nameUUIDFromBytes(merchantId.getBytes(StandardCharsets.UTF_8));
                var envelope = EventEnvelope.of(MerchantSettled.TYPE, aggregateId, 1, businessDate, null,
                        new MerchantSettled(merchantId, date, currency, netMinor, total));
                outbox.append(MerchantSettled.TOPIC, envelope, merchantId);
            }
            log.info("notified {} merchant(s) for {} (promoted count {}), day net total {}", rows.size(), date, merchantCount, total);
            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    Step notifyMerchantsStep(JobRepository jobRepository, PlatformTransactionManager tx, Tasklet notifyMerchantsTasklet) {
        return new StepBuilder("notifyMerchantsStep", jobRepository)
                .tasklet(notifyMerchantsTasklet)
                .transactionManager(tx)
                .build();
    }
}
