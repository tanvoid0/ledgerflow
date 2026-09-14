package io.ledgerflow.starter.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Three conditions: only in a servlet web app, only if the filter class is present,
 * and only if the service has not declared its own - so any service can override it
 * by writing a bean of the same type. That is the contract every Boot starter offers.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)   // a batch worker gets nothing
@ConditionalOnClass(OncePerRequestFilter.class)                                  // no web jar, no filter
public class LedgerflowWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(RequestIdFilter.class)   // a service that declares its own wins
    RequestIdFilter requestIdFilter() {
        return new RequestIdFilter();
    }
}
