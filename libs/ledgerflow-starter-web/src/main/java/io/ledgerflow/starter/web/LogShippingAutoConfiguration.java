package io.ledgerflow.starter.web;

import ch.qos.logback.classic.Logger;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingAutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Every log line goes to the same collector as the traces, carrying its trace id, so Grafana can walk from a
 * span to the lines it produced and back. Only where spans are exported: under test nothing listens on 4318,
 * and a bridge retrying against a closed port once a second is noise nobody asked for.
 */
@AutoConfiguration(after = OtlpTracingAutoConfiguration.class)
public class LogShippingAutoConfiguration {

    @Bean
    @ConditionalOnBean(SpanExporter.class)
    InitializingBean logsFollowTheTraces(OpenTelemetry openTelemetry) {
        return () -> {
            var root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
            var appender = new OpenTelemetryAppender();
            appender.setContext(root.getLoggerContext());
            appender.setCaptureMdcAttributes("*");   // requestId, paymentId: the fields a log search starts from
            appender.setOpenTelemetry(openTelemetry);
            appender.start();
            root.addAppender(appender);
        };
    }
}
