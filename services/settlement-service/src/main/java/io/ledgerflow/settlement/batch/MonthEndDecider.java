package io.ledgerflow.settlement.batch;

import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.flow.FlowExecutionStatus;
import org.springframework.batch.core.job.flow.JobExecutionDecider;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** Last day of the month gets feeReconciliationStep; every other day skips straight to afterNetting. */
@Component
class MonthEndDecider implements JobExecutionDecider {

    @Override
    public FlowExecutionStatus decide(JobExecution jobExecution, StepExecution stepExecution) {
        var date = LocalDate.parse(jobExecution.getJobParameters().getString("businessDate"));
        return date.getDayOfMonth() == date.lengthOfMonth()
                ? new FlowExecutionStatus("MONTH_END")
                : new FlowExecutionStatus("ORDINARY_DAY");
    }
}
