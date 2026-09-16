package io.ledgerflow.settlement.batch;

import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.support.CommandLineJobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.List;
import java.util.Properties;

/**
 * The operator, as a command rather than as an HTTP endpoint: a pod with no web server (the CronJobs in
 * k8s/jobs) runs under this profile, reads its verb off the command line, and hands its exit code to
 * {@link io.ledgerflow.settlement.SettlementServiceApplication#main} the same way the one-shot job does.
 */
@Configuration
@Profile("cli")
class BatchCliConfig {

    @Bean
    CommandLineJobOperator commandLineJobOperator(JobOperator jobOperator, JobRepository jobRepository, JobRegistry jobRegistry) {
        return new CommandLineJobOperator(jobOperator, jobRepository, jobRegistry);
    }

    /** Where the exit code lands: an ApplicationRunner can't call System.exit itself and still let the context close cleanly. */
    @Bean
    CliExitCode cliExitCode() {
        return new CliExitCode();
    }

    /**
     * Boot owns main(), so this reads Boot's own ApplicationArguments rather than calling
     * CommandLineJobOperator.main, which builds its own context and would give a second application.
     */
    @Bean
    ApplicationRunner batchCli(CommandLineJobOperator operator, JobRepository jobRepository, CliExitCode exitCode) {
        return (ApplicationArguments args) -> {
            List<String> rest = args.getNonOptionArgs();
            if (rest.isEmpty()) {
                throw new IllegalArgumentException(
                        "usage: start <jobName> [name=value,...] | stop|restart|abandon|recover <executionId> | recover-stranded");
            }

            String operation = rest.get(0);
            int code = switch (operation) {
                case "start" -> operator.start(rest.get(1), parameters(rest));
                case "startNextInstance" -> operator.startNextInstance(rest.get(1));
                case "stop" -> operator.stop(Long.parseLong(rest.get(1)));
                case "restart" -> operator.restart(Long.parseLong(rest.get(1)));
                case "abandon" -> operator.abandon(Long.parseLong(rest.get(1)));
                case "recover" -> operator.recover(Long.parseLong(rest.get(1)));
                case "recover-stranded" -> recoverStranded(operator, jobRepository);
                default -> throw new IllegalArgumentException("unknown operation: " + operation);
            };
            exitCode.set(code);
        };
    }

    // A money job gets a narrow sweeper: only nightlySettlementJob's stranded executions are recovered here,
    // not every job in the repository — a pod dying mid-export or mid-sweep is a lesser problem than one
    // stranded settlement run, and blanket recovery would risk resurrecting things nobody asked to be retried.
    private static int recoverStranded(CommandLineJobOperator operator, JobRepository jobRepository) {
        for (JobExecution execution : jobRepository.findRunningJobExecutions("nightlySettlementJob")) {
            operator.recover(execution.getId());
        }
        return 0;
    }

    /** name=value pairs, the format CommandLineJobOperator.start expects. */
    private static Properties parameters(List<String> rest) {
        var properties = new Properties();
        rest.stream().skip(2).forEach(pair -> {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                properties.setProperty(pair.substring(0, eq), pair.substring(eq + 1));
            }
        });
        return properties;
    }

    static class CliExitCode implements ExitCodeGenerator {
        private volatile int code;

        void set(int code) {
            this.code = code;
        }

        @Override
        public int getExitCode() {
            return code;
        }
    }
}
