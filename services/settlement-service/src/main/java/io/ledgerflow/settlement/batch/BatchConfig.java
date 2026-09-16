package io.ledgerflow.settlement.batch;

import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.EnableJdbcJobRepository;
import org.springframework.batch.core.configuration.support.MapJobRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Boot's own batch autoconfiguration backs off once a bean (or class) carries {@code @EnableBatchProcessing}
 * ({@code BatchAutoConfiguration} is {@code @ConditionalOnMissingBean(value = DefaultBatchConfiguration.class,
 * annotation = EnableBatchProcessing.class)}) — otherwise it wires an in-memory {@code ResourcelessJobRepository}
 * that forgets every job instance on restart. {@code @EnableJdbcJobRepository} needs {@code @EnableBatchProcessing}
 * alongside it (it's read by the {@code BatchRegistrar} that annotation imports); its defaults
 * (dataSourceRef/transactionManagerRef = "dataSource"/"transactionManager") already match the bean names Boot
 * auto-configures for this service's own Postgres, so nothing here needs overriding. JobOperator still comes
 * from the same registrar; JobLauncherApplicationRunner stays off via spring.batch.job.enabled: false.
 */
@Configuration
@EnableBatchProcessing
@EnableJdbcJobRepository
class BatchConfig {

    // The registrar wires a bean named jobRegistry into the JobOperator if one exists but never creates it,
    // and CommandLineJobOperator needs one (that is where "start <jobName>" looks the job up). MapJobRegistry
    // registers every Job bean itself once the context is up.
    @Bean
    MapJobRegistry jobRegistry() {
        return new MapJobRegistry();
    }
}
