package io.ledgerflow.settlement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

@ConfigurationPropertiesScan
@SpringBootApplication
public class SettlementServiceApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext ctx = SpringApplication.run(SettlementServiceApplication.class, args);
        // A pod started with spring.main.web-application-type=none is either the one-shot job or the cli
        // profile's operator command, and its runner has already finished by the time run() returns — the
        // Kafka listener threads would keep a JVM like that alive forever. A web deployment has no reason
        // to exit here at all. Close the context and exit with whichever ExitCodeGenerator is registered:
        // Boot's own JobExecutionExitCodeGenerator for the one-shot, BatchCliConfig's for the cli profile.
        if (!(ctx instanceof WebServerApplicationContext)) {
            System.exit(SpringApplication.exit(ctx));
        }
    }
}
