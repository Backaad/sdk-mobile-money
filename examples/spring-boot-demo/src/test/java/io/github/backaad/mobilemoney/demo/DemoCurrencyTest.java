package io.github.backaad.mobilemoney.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** {@code DEMO_CURRENCY=EUR}, as for the MTN MoMo public sandbox: the app is told the currency. */
@SpringBootTest(properties = "demo.currency=eur")
@AutoConfigureMockMvc
class DemoCurrencyTest {

    @Autowired
    MockMvc mvc;

    @Test
    void theAppIsToldTheCurrencyOfThePayments() throws Exception {
        mvc.perform(get("/api/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("EUR"));
        // The simulation only takes XAF and XOF: EUR is for the MTN MoMo sandbox.
        mvc.perform(post("/api/payments").header("Idempotency-Key", "currency-key-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"670123456\",\"amount\":25}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("UNSUPPORTED"));
    }
}
