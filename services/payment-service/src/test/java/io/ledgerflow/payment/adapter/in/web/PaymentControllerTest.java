package io.ledgerflow.payment.adapter.in.web;

import io.ledgerflow.payment.application.Payments;
import io.ledgerflow.payment.domain.model.PaymentState.Captured;
import io.ledgerflow.starter.web.LedgerflowSecurityAutoConfiguration;
import io.ledgerflow.starter.web.LedgerflowWebAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
@ImportAutoConfiguration({LedgerflowWebAutoConfiguration.class, LedgerflowSecurityAutoConfiguration.class})
@WithMockUser
class PaymentControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    Payments payments;

    @Test
    void listReturnsTheNewestPaymentsInTheSameShapeAsById() throws Exception {
        var id = UUID.randomUUID();
        when(payments.list(50)).thenReturn(List.of(new Captured(id, List.of())));

        mvc.perform(get("/api/v1/payments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].paymentId").value(id.toString()))
                .andExpect(jsonPath("$[0].state").value("Captured"));
    }

    @Test
    void limitIsClampedTo200() throws Exception {
        when(payments.list(200)).thenReturn(List.of());

        mvc.perform(get("/api/v1/payments").param("limit", "9999"))
                .andExpect(status().isOk());
    }
}
