package io.ledgerflow.settlement.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;

/**
 * The three triggers. {@code @EnableScheduling} isn't here — the messaging starter already turns it on, a second
 * one would just be redundant. Cron strings live in yml (settlement.batch.*-cron) so a dev can override the
 * schedule with an env var instead of a redeploy.
 */
@Component
class BatchSchedule {

    private static final Logger log = LoggerFactory.getLogger(BatchSchedule.class);

    private final JobOperator jobOperator;
    private final Job settlementJob;
    private final Job exportJob;
    private final Job sweepJob;

    BatchSchedule(JobOperator jobOperator, @Qualifier("nightlySettlementJob") Job settlementJob,
                  @Qualifier("statementExportJob") Job exportJob, @Qualifier("agedItemSweepJob") Job sweepJob) {
        this.jobOperator = jobOperator;
        this.settlementJob = settlementJob;
        this.exportJob = exportJob;
        this.sweepJob = sweepJob;
    }

    @Scheduled(cron = "${settlement.batch.settle-cron}", zone = "Europe/London")
    void settle() {
        run(settlementJob, new JobParametersBuilder().addString("businessDate", yesterday()).toJobParameters());
    }

    @Scheduled(cron = "${settlement.batch.export-cron}", zone = "Europe/London")
    void export() {
        run(exportJob, new JobParametersBuilder().addString("businessDate", yesterday()).toJobParameters());
    }

    @Scheduled(cron = "${settlement.batch.sweep-cron}", zone = "Europe/London")
    void sweep() {
        run(sweepJob, new JobParametersBuilder().addString("runAt", Instant.now().toString()).toJobParameters());
    }

    private void run(Job job, JobParameters params) {
        try {
            jobOperator.start(job, params);
        } catch (JobInstanceAlreadyCompleteException e) {
            log.info("{} already ran for {}", job.getName(), params);
        } catch (JobExecutionAlreadyRunningException e) {
            log.warn("{} still running from a previous trigger", job.getName());
        } catch (Exception e) {
            log.error("{} failed to start", job.getName(), e);
        }
    }

    private static String yesterday() {
        return LocalDate.now().minusDays(1).toString();
    }
}
