package io.ledgerflow.settlement.batch;

import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.stereotype.Component;

/**
 * Turns lineItemsStep's plain COMPLETED into the three routes nightlySettlementJob branches on. Registered on
 * the manager lineItemsStep only (see SettlementJobConfig) — a custom exit code has the highest severity in
 * ExitStatus.and(), so putting this on lineItemsWorkerStep too would let an empty-range worker's NOTHING_TO_DO
 * win the partition's aggregate over its siblings' real work.
 */
@Component
class ClassifySettlementOutcome implements StepExecutionListener {

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        if (stepExecution.getStatus().isUnsuccessful()) {
            return stepExecution.getExitStatus();
        }
        if (stepExecution.getWriteCount() == 0) {
            return new ExitStatus("NOTHING_TO_DO");
        }
        if (stepExecution.getSkipCount() > 0) {
            return new ExitStatus("COMPLETED_WITH_REJECTS");
        }
        return ExitStatus.COMPLETED;
    }
}
