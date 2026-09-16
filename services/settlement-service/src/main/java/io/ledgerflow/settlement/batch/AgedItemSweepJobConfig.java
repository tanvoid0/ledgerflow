package io.ledgerflow.settlement.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/** A NEW item two days past its business date never settled and isn't going to; sweep it to STALE so it stops showing up in the nightly read. */
@Configuration
class AgedItemSweepJobConfig {

    private static final Logger log = LoggerFactory.getLogger(AgedItemSweepJobConfig.class);

    @Bean
    Tasklet agedItemSweepTasklet(JdbcClient db) {
        return (contribution, chunkContext) -> {
            int moved = db.sql("""
                    update settlement_item set status = 'STALE'
                    where status = 'NEW' and business_date < current_date - interval '2 days'
                    """).update();
            contribution.incrementWriteCount(moved);
            log.info("swept {} aged settlement_item rows to STALE", moved);
            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    Step agedItemSweepStep(JobRepository jobRepository, PlatformTransactionManager tx, Tasklet agedItemSweepTasklet) {
        return new StepBuilder("agedItemSweepStep", jobRepository)
                .tasklet(agedItemSweepTasklet)
                .transactionManager(tx)
                .build();
    }

    @Bean
    Job agedItemSweepJob(JobRepository jobRepository, Step agedItemSweepStep) {
        return new JobBuilder("agedItemSweepJob", jobRepository)
                .start(agedItemSweepStep)
                .build();
    }
}
