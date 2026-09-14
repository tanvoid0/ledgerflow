package io.ledgerflow.issuer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@ConfigurationPropertiesScan
@SpringBootApplication
public class IssuerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IssuerServiceApplication.class, args);
    }
}
