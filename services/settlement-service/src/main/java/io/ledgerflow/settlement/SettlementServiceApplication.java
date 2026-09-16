package io.ledgerflow.settlement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;

@ConfigurationPropertiesScan
@SpringBootApplication
public class SettlementServiceApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext ctx = SpringApplication.run(SettlementServiceApplication.class, args);
        // As a one-shot job (spring.batch.job.enabled=true) the runner has already finished by the time
        // run() returns, but the Kafka listener threads would keep the JVM alive forever. Close the
        // context and exit with Boot's JobExecutionExitCodeGenerator code: 0 for COMPLETED, 1 otherwise.
        if (ctx.getEnvironment().getProperty("spring.batch.job.enabled", Boolean.class, false)) {
            System.exit(SpringApplication.exit(ctx));
        }
    }
}
