package io.ledgerflow.gateway;

import io.ledgerflow.starter.web.RequestIdFilter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.server.mvc.filter.HttpHeadersFilter.ResponseHttpHeadersFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling   // the stream's heartbeat; no starter-messaging here to bring it in
@SpringBootApplication
public class GatewayServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayServiceApplication.class, args);
    }

    /** The routed service echoes X-Request-Id as well; the gateway's filter already set it, so drop the second copy. */
    @Bean
    ResponseHttpHeadersFilter oneRequestIdPerResponse() {
        return (headers, response) -> {
            var copy = new HttpHeaders();
            copy.addAll(headers);
            copy.remove(RequestIdFilter.HEADER);
            return copy;
        };
    }
}
