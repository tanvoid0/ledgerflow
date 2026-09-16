package io.ledgerflow.settlement.batch;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.stereotype.Component;

/** Counts written/skipped items per step, tagged by step name — a partitioned run's workers show up as lineItemsWorkerStep:partition0 etc. */
@Component
class SettlementMetrics implements StepExecutionListener {

    private final MeterRegistry meters;

    SettlementMetrics(MeterRegistry meters) {
        this.meters = meters;
    }

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        String step = stepExecution.getStepName();
        meters.counter("ledgerflow.settlement.items.written", "step", step).increment(stepExecution.getWriteCount());
        meters.counter("ledgerflow.settlement.items.skipped", "step", step).increment(stepExecution.getSkipCount());
        return stepExecution.getExitStatus();
    }
}
