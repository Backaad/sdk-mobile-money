package io.github.backaad.mobilemoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.backaad.mobilemoney.provider.simulation.SimulationProvider;
import io.github.backaad.mobilemoney.testing.StubProvider;
import org.junit.jupiter.api.Test;

/** Rule 8: the simulation never confirms a payment. */
class SimulationTest {

    private final MobileMoney mobileMoney = MobileMoney.builder().provider(new SimulationProvider()).build();

    @Test
    void paymentsStayPendingForever() {
        Transaction transaction = mobileMoney.collect(PaymentRequest.builder().idempotencyKey("sim-1").amount(100)
                .phoneNumber("670123456").merchantReference("order-1").description("Test").build());

        assertThat(transaction.provider()).isEqualTo(ProviderId.SIMULATION);
        assertThat(transaction.status()).isEqualTo(TransactionStatus.PENDING);
        for (int i = 0; i < 20; i++) {
            assertThat(mobileMoney.status(transaction).status()).isEqualTo(TransactionStatus.PENDING);
        }
    }

    @Test
    void payoutsStayPendingForever() {
        Transaction transaction = mobileMoney.payout(PayoutRequest.builder().idempotencyKey("sim-2").amount(100)
                .phoneNumber("690123456").beneficiaryName("Test").merchantReference("payout-1")
                .description("Test").build());

        assertThat(mobileMoney.status(transaction).status()).isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void webhooksAreAlwaysRejected() {
        WebhookRequest claimsSuccess = WebhookRequest.builder().method("POST")
                .body("{\"status\": \"SUCCESSFUL\", \"reference\": \"sim-1\"}").build();

        assertThatThrownBy(() -> mobileMoney.handleWebhook(ProviderId.SIMULATION, claimsSuccess))
                .isInstanceOf(WebhookVerificationException.class)
                .hasMessageContaining("never confirms");
    }

    @Test
    void cannotBeMixedWithRealProviders() {
        assertThatThrownBy(() -> MobileMoney.builder().provider(new StubProvider("campay"))
                .provider(new SimulationProvider()).build())
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("simulation cannot be combined");
    }
}
