package io.ledgerflow.starter.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;

/**
 * Puts a request id on every log line and echoes it back so a caller can quote it.
 * No @Component: the auto-configuration decides whether this becomes a bean.
 */
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        var id = req.getHeader(HEADER);
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
            req = withRequestId(req, id);   // so whatever reads the request next - the gateway proxying it on - carries the same id
        }
        MDC.put(MDC_KEY, id);
        res.setHeader(HEADER, id);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove(MDC_KEY);   // threads are pooled; always clean up
        }
    }

    private static HttpServletRequest withRequestId(HttpServletRequest req, String id) {
        return new HttpServletRequestWrapper(req) {
            @Override
            public String getHeader(String name) {
                return HEADER.equalsIgnoreCase(name) ? id : super.getHeader(name);
            }

            @Override
            public Enumeration<String> getHeaders(String name) {
                return HEADER.equalsIgnoreCase(name) ? Collections.enumeration(List.of(id)) : super.getHeaders(name);
            }

            @Override
            public Enumeration<String> getHeaderNames() {
                var names = Collections.list(super.getHeaderNames());
                if (names.stream().noneMatch(HEADER::equalsIgnoreCase)) names.add(HEADER);   // present but blank: already listed
                return Collections.enumeration(names);
            }
        };
    }
}
