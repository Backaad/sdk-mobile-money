package io.github.backaad.mobilemoney;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.REFERENCE;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.TOKEN;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.fixture;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.json;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.okJson;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.stubToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.backaad.mobilemoney.testing.CampayTestServer;
import io.github.backaad.mobilemoney.testing.MutableClock;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Rule 4: a transient error while reading a status (network, 5xx, timeout, empty response) leaves the
 * payment as it was. It never marks it as failed.
 */
class MobileMoneyStatusTest {

    @RegisterExtension
    static WireMockExtension campay = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private final MutableClock clock = MutableClock.at("2026-10-10T12:00:00Z");
    private MobileMoney mobileMoney;

    private static final Transaction PENDING = new Transaction(ProviderId.CAMPAY, TransactionType.COLLECTION,
            REFERENCE, "order-1234", 5, Currencies.XAF, TransactionStatus.PENDING, null);

    @BeforeEach
    void setUp() {
        stubToken(campay, TOKEN, 3600);
        mobileMoney = MobileMoney.builder().provider(CampayTestServer.provider(campay, clock).build()).clock(clock)
                .build();
    }

    private static void stubStatus(ResponseDefinitionBuilder response) {
        campay.stubFor(get(urlEqualTo("/api/transaction/" + REFERENCE + "/")).willReturn(response));
    }

    static Stream<Arguments> transientFailures() {
        return Stream.of(
                Arguments.of(Named.of("HTTP 500", aResponse().withStatus(500))),
                Arguments.of(Named.of("HTTP 502", aResponse().withStatus(502))),
                Arguments.of(Named.of("HTTP 503", aResponse().withStatus(503))),
                Arguments.of(Named.of("HTTP 504", aResponse().withStatus(504))),
                Arguments.of(Named.of("HTTP 429", aResponse().withStatus(429))),
                Arguments.of(Named.of("timeout", okJson(fixture("transaction-failed.json")).withFixedDelay(2_000))),
                Arguments.of(Named.of("connection reset", aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))),
                Arguments.of(Named.of("empty response", aResponse().withFault(Fault.EMPTY_RESPONSE))),
                Arguments.of(Named.of("200 without body", aResponse().withStatus(200))),
                Arguments.of(Named.of("200 not JSON", aResponse().withStatus(200).withBody("<html>oops</html>"))),
                Arguments.of(Named.of("200 without status", okJson("{}"))));
    }

    @ParameterizedTest
    @MethodSource("transientFailures")
    void transientFailureLeavesAPendingPaymentPending(ResponseDefinitionBuilder failure) {
        stubStatus(failure);

        Transaction after = mobileMoney.status(PENDING);

        assertThat(after.status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(after).isEqualTo(PENDING);
    }

    @ParameterizedTest
    @MethodSource("transientFailures")
    void transientFailureNeverUndoesAKnownOutcome(ResponseDefinitionBuilder failure) {
        stubStatus(failure);
        Transaction successful = PENDING.withStatus(TransactionStatus.SUCCESSFUL, "SUCCESSFUL");

        assertThat(mobileMoney.status(successful).status()).isEqualTo(TransactionStatus.SUCCESSFUL);
    }

    @Test
    void unreachableProviderLeavesThePaymentPending() {
        MobileMoney unreachable = MobileMoney.builder().provider(CampayTestServer.provider(campay, clock)
                .baseUrl(java.net.URI.create("http://localhost:1")).build()).build();

        assertThat(unreachable.status(PENDING).status()).isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void finalStatusesComeOnlyFromTheProvider() {
        stubStatus(okJson(fixture("transaction-failed.json")));
        assertThat(mobileMoney.status(PENDING).status()).isEqualTo(TransactionStatus.FAILED);

        stubStatus(okJson(fixture("transaction-successful.json")));
        Transaction successful = mobileMoney.status(PENDING);
        assertThat(successful.status()).isEqualTo(TransactionStatus.SUCCESSFUL);
        assertThat(successful.rawStatus()).isEqualTo("SUCCESSFUL");
        assertThat(successful.provider()).isEqualTo(ProviderId.CAMPAY);
    }

    @Test
    void unknownProviderStatusIsPendingWithTheRawStatusKept() {
        stubStatus(okJson("{\"reference\": \"" + REFERENCE + "\", \"status\": \"EXPIRED_MAYBE\"}"));

        Transaction after = mobileMoney.status(PENDING);

        assertThat(after.status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(after.rawStatus()).isEqualTo("EXPIRED_MAYBE");
    }

    @Test
    void notFoundIsReportedButNeverAFailure() {
        stubStatus(json(404, "{\"message\": \"Transaction not found\"}"));

        assertThatThrownBy(() -> mobileMoney.status(PENDING)).isInstanceOf(TransactionNotFoundException.class);
    }

    @Test
    void statusOfAnotherTransactionIsIgnored() {
        stubStatus(okJson("{\"reference\": \"" + REFERENCE + "\", \"external_reference\": \"someone-else\","
                + " \"status\": \"SUCCESSFUL\"}"));

        assertThat(mobileMoney.status(PENDING)).isEqualTo(PENDING);
    }

    @Test
    void unknownOutcomeCannotBeReadAndStaysPending() {
        Transaction unknown = new Transaction(ProviderId.CAMPAY, TransactionType.COLLECTION, null, "order-1234", 5,
                Currencies.XAF, TransactionStatus.PENDING, null);

        assertThat(mobileMoney.status(unknown)).isEqualTo(unknown);
    }
}
