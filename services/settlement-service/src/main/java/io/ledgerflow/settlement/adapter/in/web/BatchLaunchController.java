package io.ledgerflow.settlement.adapter.in.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A hand trigger for the nightly job: POST a business date, get the run back. Step 26 puts a CronJob in
 * front of this on a schedule; until then this is how the job starts, on the request thread.
 */
@RestController
@RequestMapping("/api/v1/batch/jobs")
@RequiredArgsConstructor
class BatchLaunchController {

    private final JobOperator jobOperator;
    private final List<Job> jobs;

    record LaunchRequest(@NotBlank String businessDate) {}

    record JobExecutionResponse(String jobName, long executionId, String status, String exitCode,
                                 LocalDateTime startTime, LocalDateTime endTime) {
        static JobExecutionResponse of(String jobName, JobExecution e) {
            return new JobExecutionResponse(jobName, e.getId(), e.getStatus().name(), e.getExitStatus().getExitCode(),
                    e.getStartTime(), e.getEndTime());
        }
    }

    @PostMapping("/{name}")
    JobExecutionResponse launch(@PathVariable String name, @Valid @RequestBody LaunchRequest req) throws Exception {
        var job = jobs.stream().filter(j -> j.getName().equals(name)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no job named " + name));
        var execution = jobOperator.start(job,
                new JobParametersBuilder().addString("businessDate", req.businessDate()).toJobParameters());
        return JobExecutionResponse.of(name, execution);
    }

    /** A second POST for the same business date: the job already ran to completion for it, running it again is a no-op request, not a server error. */
    @ExceptionHandler(JobInstanceAlreadyCompleteException.class)
    ProblemDetail onAlreadyComplete(JobInstanceAlreadyCompleteException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}
