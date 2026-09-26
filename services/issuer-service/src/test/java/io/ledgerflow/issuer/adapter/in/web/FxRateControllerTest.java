package io.ledgerflow.issuer.adapter.in.web;

import io.ledgerflow.starter.web.LedgerflowSecurityAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;

@WebMvcTest(FxRateController.class)
@ImportAutoConfiguration(LedgerflowSecurityAutoConfiguration.class)   // the slice does not scan our starter
@WithMockUser
class FxRateControllerTest {

    @Autowired
    private MockMvcTester mvc;

    @Test
    void twoArmedFaultsThenARate() {
        mvc.post().uri("/api/v1/fx/faults?count=2").exchange();

        assertThat(mvc.get().uri("/api/v1/fx/USD").exchange())
                .hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(mvc.get().uri("/api/v1/fx/USD").exchange())
                .hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(mvc.get().uri("/api/v1/fx/USD").exchange())
                .hasStatusOk()
                .bodyText().isEqualTo("25");
    }

    @Test
    void gbpNeedsNoUplift() {
        assertThat(mvc.get().uri("/api/v1/fx/GBP").exchange())
                .hasStatusOk()
                .bodyText().isEqualTo("0");
    }
}
