package io.ledgerflow.gateway;

import io.ledgerflow.starter.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** The id the gateway mints for a caller that sent none is the one the proxied request carries downstream. */
class RequestIdForwardingTest {

    @Test
    void aMintedIdRidesOnTheRequestItWasMintedFor() throws Exception {
        var res = new MockHttpServletResponse();
        var forwarded = new AtomicReference<HttpServletRequest>();

        new RequestIdFilter().doFilter(new MockHttpServletRequest("GET", "/api/v1/accounts"), res,
                (req, r) -> forwarded.set((HttpServletRequest) req));

        var id = res.getHeader(RequestIdFilter.HEADER);
        assertThat(id).isNotBlank();
        assertThat(forwarded.get().getHeader(RequestIdFilter.HEADER)).isEqualTo(id);
        assertThat(Collections.list(forwarded.get().getHeaders(RequestIdFilter.HEADER))).containsExactly(id);
        assertThat(Collections.list(forwarded.get().getHeaderNames())).containsOnlyOnce(RequestIdFilter.HEADER);
    }

    @Test
    void aCallersIdPassesThroughUntouched() throws Exception {
        var req = new MockHttpServletRequest("GET", "/api/v1/accounts");
        req.addHeader(RequestIdFilter.HEADER, "from-the-browser");
        var forwarded = new AtomicReference<HttpServletRequest>();

        new RequestIdFilter().doFilter(req, new MockHttpServletResponse(), (r, s) -> forwarded.set((HttpServletRequest) r));

        assertThat(forwarded.get()).isSameAs(req);
    }
}
