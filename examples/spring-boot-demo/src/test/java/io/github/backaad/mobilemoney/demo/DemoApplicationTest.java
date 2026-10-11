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

/** The demo in simulation mode: payments start and stay pending, webhooks are refused. */
@SpringBootTest
@AutoConfigureMockMvc
class DemoApplicationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void paymentStartsAndStaysPendingInSimulation() throws Exception {
        String body = "{\"phoneNumber\":\"+237 670 12 34 56\",\"amount\":500}";
        mvc.perform(post("/api/payments").header("Idempotency-Key", "test-key-0001")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reference").value("demo-test-key-0001"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.provider").value("simulation"));
        // A retry with the same key gets the same payment.
        mvc.perform(post("/api/payments").header("Idempotency-Key", "test-key-0001")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reference").value("demo-test-key-0001"));
        mvc.perform(get("/api/payments/demo-test-key-0001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void badRequestsAreExplained() throws Exception {
        mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"670123456\",\"amount\":500}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_KEY_REQUIRED"));
        mvc.perform(post("/api/payments").header("Idempotency-Key", "test-key-0002")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phoneNumber\":\"12\",\"amount\":500}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PHONE_NUMBER"));
        mvc.perform(post("/api/payments").header("Idempotency-Key", "test-key-0003")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phoneNumber\":\"670123456\",\"amount\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_AMOUNT"));
        mvc.perform(post("/api/payments").header("Idempotency-Key", "test-key-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"670123456\",\"amount\":999}"))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/payments/demo-unknown")).andExpect(status().isNotFound());
    }

    @Test
    void configGivesTheCurrency() throws Exception {
        mvc.perform(get("/api/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("XAF"));
    }

    @Test
    void simulationRefusesEveryWebhook() throws Exception {
        mvc.perform(post("/mobile-money/webhooks/simulation").content("{\"status\":\"SUCCESSFUL\"}"))
                .andExpect(status().isBadRequest());
    }
}
