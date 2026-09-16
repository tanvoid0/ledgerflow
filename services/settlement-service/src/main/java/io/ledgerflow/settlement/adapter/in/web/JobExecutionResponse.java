package io.ledgerflow.settlement.adapter.in.web;

import org.springframework.batch.core.job.JobExecution;

import java.time.LocalDateTime;

record JobExecutionResponse(String jobName, long executionId, String status, String exitCode,
                             LocalDateTime startTime, LocalDateTime endTime) {
    static JobExecutionResponse of(String jobName, JobExecution e) {
        return new JobExecutionResponse(jobName, e.getId(), e.getStatus().name(), e.getExitStatus().getExitCode(),
                e.getStartTime(), e.getEndTime());
    }
}
