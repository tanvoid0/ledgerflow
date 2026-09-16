package io.ledgerflow.settlement.batch;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.launch.support.CommandLineJobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The runner's switch, against a mocked operator — no context, no Testcontainers. What matters here is the
 * exit code CommandLineJobOperator hands back reaches the ExitCodeGenerator bean unchanged, since that bean
 * is the only thing a CronJob reads.
 */
class BatchCliConfigTest {

    private final BatchCliConfig config = new BatchCliConfig();
    private final CommandLineJobOperator operator = mock(CommandLineJobOperator.class);
    private final JobRepository jobRepository = mock(JobRepository.class);
    private final BatchCliConfig.CliExitCode exitCode = config.cliExitCode();
    private final ApplicationRunner runner = config.batchCli(operator, jobRepository, exitCode);

    @Test
    void startOfACompletingJobExitsZero() throws Exception {
        when(operator.start(eq("agedItemSweepJob"), any(Properties.class))).thenReturn(0);

        runner.run(argsOf("start", "agedItemSweepJob", "runAt=2026-09-16"));

        assertThat(exitCode.getExitCode()).isEqualTo(0);
        var captor = org.mockito.ArgumentCaptor.forClass(Properties.class);
        verify(operator).start(eq("agedItemSweepJob"), captor.capture());
        assertThat(captor.getValue()).containsEntry("runAt", "2026-09-16");
    }

    @Test
    void unknownOperationIsAUsageException() {
        assertThatThrownBy(() -> runner.run(argsOf("bogus")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown operation");
    }

    @Test
    void noArgumentsIsAUsageException() {
        assertThatThrownBy(() -> runner.run(argsOf()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("usage:");
    }

    @Test
    void recoverStrandedWithNothingStrandedExitsZero() throws Exception {
        when(jobRepository.findRunningJobExecutions("nightlySettlementJob")).thenReturn(Set.of());

        runner.run(argsOf("recover-stranded"));

        assertThat(exitCode.getExitCode()).isEqualTo(0);
        verify(operator, never()).recover(anyLong());
    }

    @Test
    void recoverStrandedRecoversEveryRunningExecutionOfTheSettlementJobOnly() throws Exception {
        var stranded = mock(JobExecution.class);
        when(stranded.getId()).thenReturn(42L);
        when(jobRepository.findRunningJobExecutions("nightlySettlementJob")).thenReturn(Set.of(stranded));

        runner.run(argsOf("recover-stranded"));

        assertThat(exitCode.getExitCode()).isEqualTo(0);
        verify(operator).recover(42L);
    }

    private static ApplicationArguments argsOf(String... nonOptionArgs) {
        var args = mock(ApplicationArguments.class);
        when(args.getNonOptionArgs()).thenReturn(List.of(nonOptionArgs));
        return args;
    }
}
