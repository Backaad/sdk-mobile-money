package io.github.backaad.mobilemoney;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.PASSWORD;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.REFERENCE;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.TOKEN;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.TOKEN_2;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.USERNAME;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.WEBHOOK_KEY;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.fixture;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.json;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.okJson;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.stubToken;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.tokenRequest;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.github.backaad.mobilemoney.provider.campay.CampayProvider;
import io.github.backaad.mobilemoney.testing.CampayTestServer;
import io.github.backaad.mobilemoney.testing.MutableClock;
import io.github.backaad.mobilemoney.testing.TestJwt;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.LoggerFactory;

/**
 * Rule 7: no key, token, signature or body that contains one ends up in the logs. The test credentials are
 * distinctive "canary" values; every log event (at TRACE), exception message, cause and {@code toString()}
 * produced by a set of Campay scenarios is searched for them.
 */
class NoSecretsLeakTest {

    @RegisterExtension
    static WireMockExtension campay = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private static final long NOW = 1791633600L;
    private static final String SIGNATURE = TestJwt.hs256("{\"exp\":" + (NOW + 300) + "}", WEBHOOK_KEY);
    private static final String FORGED = TestJwt.hs256("{\"exp\":" + (NOW + 300) + "}", "CANARY-FORGED-KEY-0a1b");

    private final MutableClock clock = MutableClock.at("2026-10-10T12:00:00Z");
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final List<String> observed = new ArrayList<>();
    private Logger sdkLogger;

    @BeforeEach
    void captureLogs() {
        sdkLogger = (Logger) LoggerFactory.getLogger("io.github.backaad.mobilemoney");
        logs.start();
        sdkLogger.addAppender(logs);
    }

    @AfterEach
    void stopCapture() {
        sdkLogger.detachAppender(logs);
    }

    @Test
    void secretsNeverLeak() {
        CampayProvider provider = CampayTestServer.provider(campay, clock).build();
        MobileMoney mobileMoney = MobileMoney.builder().provider(provider).clock(clock).build();
        observed.add(provider.toString());

        // Token renewal after a 401.
        campay.stubFor(tokenRequest().inScenario("t").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson("{\"token\": \"" + TOKEN + "\", \"expires_in\": 3600}")).willSetStateTo("2"));
        campay.stubFor(tokenRequest().inScenario("t").whenScenarioStateIs("2")
                .willReturn(okJson("{\"token\": \"" + TOKEN_2 + "\", \"expires_in\": 3600}")));
        campay.stubFor(post(urlEqualTo("/api/collect/")).withHeader("Authorization", equalTo("Token " + TOKEN))
                .willReturn(json(401, "{\"detail\": \"Invalid token " + TOKEN + "\"}")));
        campay.stubFor(post(urlEqualTo("/api/collect/")).withHeader("Authorization", equalTo("Token " + TOKEN_2))
                .willReturn(okJson(fixture("collect-200.json"))));
        run(() -> mobileMoney.collect(payment("a")));

        // A refusal whose message repeats the token and the password.
        campay.stubFor(post(urlEqualTo("/api/collect/")).willReturn(json(400,
                "{\"message\": \"bad request with " + TOKEN_2 + " and " + PASSWORD + "\", \"error_code\": \"ER101\"}")));
        run(() -> mobileMoney.collect(payment("b")));
        run(() -> mobileMoney.collect(payment("b")));

        // Server error, broken connection, unreadable answers.
        campay.stubFor(post(urlEqualTo("/api/collect/")).willReturn(aResponse().withStatus(500)
                .withBody("Traceback: password=" + PASSWORD + " token=" + TOKEN_2)));
        run(() -> mobileMoney.collect(payment("c")));
        campay.stubFor(post(urlEqualTo("/api/collect/")).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        run(() -> mobileMoney.collect(payment("d")));
        campay.stubFor(post(urlEqualTo("/api/collect/")).willReturn(aResponse().withStatus(200)
                .withBody("{\"reference\": " + TOKEN_2)));
        run(() -> mobileMoney.collect(payment("e")));

        // Status reads, including an unreadable one.
        Transaction pending = new Transaction(ProviderId.CAMPAY, TransactionType.COLLECTION, REFERENCE, "order-1234",
                100, Currencies.XAF, TransactionStatus.PENDING, null);
        campay.stubFor(get(urlEqualTo("/api/transaction/" + REFERENCE + "/")).willReturn(aResponse().withStatus(200)
                .withBody("not json " + TOKEN_2)));
        run(() -> mobileMoney.status(pending));
        campay.stubFor(get(urlEqualTo("/api/transaction/" + REFERENCE + "/"))
                .willReturn(okJson(fixture("transaction-successful.json"))));
        run(() -> mobileMoney.status(pending));

        // Webhooks: valid, forged, malformed.
        run(() -> mobileMoney.handleWebhook(ProviderId.CAMPAY, webhook(SIGNATURE)));
        run(() -> mobileMoney.handleWebhook(ProviderId.CAMPAY, webhook(FORGED)));
        run(() -> mobileMoney.handleWebhook(ProviderId.CAMPAY, webhook(SIGNATURE + "x")));
        observed.add(webhook(SIGNATURE).toString());

        // Wrong credentials, and a token endpoint answering garbage that contains the credentials.
        campay.stubFor(tokenRequest().willReturn(json(400,
                "{\"non_field_errors\": [\"" + USERNAME + " / " + PASSWORD + " refused\"]}")));
        run(() -> MobileMoney.builder().provider(CampayTestServer.provider(campay, clock).build()).build()
                .collect(payment("f")));
        campay.stubFor(tokenRequest().willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/plain")
                .withBody(TOKEN + " " + PASSWORD)));
        run(() -> MobileMoney.builder().provider(CampayTestServer.provider(campay, clock).build()).build()
                .collect(payment("g")));

        for (ILoggingEvent event : logs.list) {
            observed.add(event.getFormattedMessage());
            if (event.getArgumentArray() != null) {
                for (Object argument : event.getArgumentArray()) {
                    observed.add(String.valueOf(argument));
                }
            }
            observed.add(String.valueOf(event.getMDCPropertyMap()));
            for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause()) {
                observed.add(t.getClassName() + ": " + t.getMessage());
            }
        }

        assertThat(logs.list).isNotEmpty();
        List<String> canaries = List.of(USERNAME, PASSWORD, WEBHOOK_KEY, TOKEN, TOKEN_2, "CANARY",
                SIGNATURE.split("\\.")[2], FORGED.split("\\.")[2]);
        for (String text : observed) {
            for (String canary : canaries) {
                assertThat(text).as("must not contain a secret").doesNotContain(canary);
            }
        }
    }

    private static PaymentRequest payment(String key) {
        return PaymentRequest.builder().idempotencyKey(key).amount(100).phoneNumber("670123456")
                .merchantReference("order-1234").description("Order 1234").build();
    }

    private static WebhookRequest webhook(String signature) {
        return WebhookRequest.builder().method("GET")
                .rawQuery("status=SUCCESSFUL&reference=" + REFERENCE + "&external_reference=order-1234&signature="
                        + URLEncoder.encode(signature, StandardCharsets.UTF_8))
                .build();
    }

    /** Runs a call and records its result or exception, with the whole cause chain and stack trace. */
    private void run(Runnable call) {
        try {
            call.run();
        } catch (RuntimeException e) {
            StringWriter trace = new StringWriter();
            e.printStackTrace(new PrintWriter(trace));
            observed.add(trace.toString());
            observed.add(e.toString());
        }
    }

    private interface Runnable {
        Object run();
    }
}
