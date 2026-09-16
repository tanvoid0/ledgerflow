package io.ledgerflow.settlement.adapter.in.web;

import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.launch.JobExecutionNotRunningException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Restarting or recovering a run by its execution id, once it's already failed. */
@RestController
@RequestMapping("/api/v1/batch/executions")
@RequiredArgsConstructor
class BatchExecutionController {

    private final JobOperator jobOperator;
    private final JobRepository jobRepository;

    @PostMapping("/{executionId}/restart")
    @ResponseStatus(HttpStatus.ACCEPTED)
    JobExecutionResponse restart(@PathVariable long executionId) throws Exception {
        var execution = jobExecution(executionId);
        var restarted = jobOperator.restart(execution);
        return JobExecutionResponse.of(restarted.getJobInstance().getJobName(), restarted);
    }

    @PostMapping("/{executionId}/recover")
    JobExecutionResponse recover(@PathVariable long executionId) {
        var execution = jobExecution(executionId);
        var recovered = jobOperator.recover(execution);
        return JobExecutionResponse.of(recovered.getJobInstance().getJobName(), recovered);
    }

    /** A stop is a request, not an abort: the step finishes its current chunk/tasklet and the run ends STOPPED, restartable later. */
    @PostMapping("/{executionId}/stop")
    @ResponseStatus(HttpStatus.ACCEPTED)
    JobExecutionResponse stop(@PathVariable long executionId) {
        var execution = jobExecution(executionId);
        try {
            jobOperator.stop(execution);
        } catch (JobExecutionNotRunningException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        return JobExecutionResponse.of(execution.getJobInstance().getJobName(), jobRepository.getJobExecution(executionId));
    }

    private JobExecution jobExecution(long executionId) {
        var execution = jobRepository.getJobExecution(executionId);
        if (execution == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no job execution " + executionId);
        }
        return execution;
    }
}
