package io.github.backaad.mobilemoney;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.backaad.mobilemoney.internal.Json;
import io.github.backaad.mobilemoney.provider.mtnmomo.MtnMomoEnvironment;
import io.github.backaad.mobilemoney.provider.mtnmomo.MtnMomoProvider;
import io.github.backaad.mobilemoney.provider.notchpay.NotchPayProvider;
import io.github.backaad.mobilemoney.provider.tara.TaraProvider;
import io.github.backaad.mobilemoney.testing.MutableClock;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.LoggerFactory;

/**
 * Rule 7 for MTN MoMo, NotchPay and Tara: their keys, tokens, signatures and single-use callback tokens
 * never appear in logs (at TRACE), exception messages, causes or {@code toString()}, even when a provider
 * repeats them in an error answer.
 */
class NoSecretsLeakOtherProvidersTest {

    private static final String MTN_SUBSCRIPTION = "CANARY-MTN-SUB-0a1b";
    private static final String MTN_USER = "CANARY-MTN-USER-2c3d";
    private static final String MTN_KEY = "CANARY-MTN-KEY-4e5f";
    private static final String MTN_TOKEN = "CANARY-MTN-TOKEN-6a7b";
    private static final String NOTCH_PUBLIC = "CANARY-NOTCH-PUB-8c9d";
    private static final String NOTCH_PRIVATE = "CANARY-NOTCH-PRIV-0e1f";
    private static final String NOTCH_SECRET = "CANARY-NOTCH-HASH-2a3b";
    private static final String TARA_KEY = "CANARY-TARA-KEY-4c5d";
    private static final String TARA_BUSINESS = "CANARY-TARA-BIZ-6e7f";

    @RegisterExtension
    static WireMockExtension server = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

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
        URI base = URI.create(server.baseUrl());
        MtnMomoProvider mtn = MtnMomoProvider.builder().environment(MtnMomoEnvironment.SANDBOX).baseUrl(base)
                .collection(MTN_SUBSCRIPTION, MTN_USER, MTN_KEY).requestTimeout(Duration.ofMillis(500))
                .clock(clock).build();
        NotchPayProvider notch = NotchPayProvider.builder().baseUrl(base).publicKey(NOTCH_PUBLIC)
                .privateKey(NOTCH_PRIVATE).webhookSecret(NOTCH_SECRET).requestTimeout(Duration.ofMillis(500)).build();
        TaraProvider tara = TaraProvider.builder().baseUrl(base).apiKey(TARA_KEY).businessId(TARA_BUSINESS)
                .requestTimeout(Duration.ofMillis(500)).build();
        observed.add(mtn.toString());
        observed.add(notch.toString());
        observed.add(tara.toString());

        // MTN MoMo: credentials refused with an answer repeating them, then refusals repeating the token.
        server.stubFor(post(urlEqualTo("/collection/token/")).willReturn(aResponse().withStatus(401)
                .withBody("{\"message\":\"" + MTN_USER + ":" + MTN_KEY + " refused\"}")));
        run(() -> MobileMoney.builder().provider(mtn).build().collect(payment("m1", null)));
        server.stubFor(post(urlEqualTo("/collection/token/")).willReturn(aResponse().withStatus(200)
                .withBody("{\"access_token\":\"" + MTN_TOKEN + "\",\"expires_in\":3600}")));
        server.stubFor(post(urlEqualTo("/collection/v1_0/requesttopay")).willReturn(aResponse().withStatus(400)
                .withBody("{\"code\":\"" + MTN_TOKEN + "\",\"message\":\"bad " + MTN_SUBSCRIPTION + " " + MTN_TOKEN
                        + "\"}")));
        MobileMoney mtnMoney = MobileMoney.builder().provider(mtn).build();
        run(() -> mtnMoney.collect(payment("m2", "https://shop.example/cb")));
        server.stubFor(post(urlEqualTo("/collection/v1_0/requesttopay")).willReturn(aResponse().withStatus(500)
                .withBody("Traceback " + MTN_KEY + " " + MTN_TOKEN)));
        run(() -> mtnMoney.collect(payment("m3", null)));
        server.stubFor(post(urlEqualTo("/collection/v1_0/requesttopay"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        run(() -> mtnMoney.collect(payment("m4", null)));
        server.stubFor(get(urlPathMatching("/collection/v1_0/requesttopay/.*")).willReturn(aResponse()
                .withStatus(200).withBody("not json " + MTN_TOKEN)));
        run(() -> mtnMoney.status(new Transaction(ProviderId.MTN_MOMO, TransactionType.COLLECTION,
                "3f2a8c1e-5b6d-4e7f-8a9b-0c1d2e3f4a5b", "order-1", 100, Currencies.XAF, TransactionStatus.PENDING,
                null)));

        // NotchPay: refusals and errors repeating the keys, forged and missing webhook signatures.
        MobileMoney notchMoney = MobileMoney.builder().provider(notch).build();
        server.stubFor(post(urlEqualTo("/payments")).willReturn(aResponse().withStatus(422)
                .withBody("{\"message\":\"invalid " + NOTCH_PUBLIC + " / " + NOTCH_PRIVATE + "\"}")));
        run(() -> notchMoney.collect(payment("n1", null)));
        server.stubFor(post(urlEqualTo("/payments")).willReturn(aResponse().withStatus(401)
                .withBody("{\"message\":\"" + NOTCH_PUBLIC + "\"}")));
        run(() -> notchMoney.collect(payment("n2", null)));
        server.stubFor(post(urlEqualTo("/transfers")).willReturn(aResponse().withStatus(500)
                .withBody(NOTCH_PRIVATE)));
        run(() -> notchMoney.payout(PayoutRequest.builder().idempotencyKey("n3").amount(100).phoneNumber("670123456")
                .merchantReference("p-1").description("d").beneficiaryName("Jane").build()));
        run(() -> notchMoney.handleWebhook(ProviderId.NOTCHPAY, WebhookRequest.builder().method("POST")
                .header(NotchPayProvider.SIGNATURE_HEADER, "0".repeat(64))
                .body("{\"type\":\"payment.complete\",\"data\":{\"reference\":\"trx.1\"}}").build()));
        run(() -> notchMoney.handleWebhook(ProviderId.NOTCHPAY, WebhookRequest.builder().method("POST")
                .body("{\"secret\":\"" + NOTCH_SECRET + "\"}").build()));

        // Tara: refusals repeating the key, and the single-use callback token.
        MobileMoney taraMoney = MobileMoney.builder().provider(tara).clock(clock).build();
        server.stubFor(post(urlEqualTo("/api/tara/mobilepay")).willReturn(aResponse().withStatus(200)
                .withBody("{\"status\":\"FAILURE\",\"message\":\"apiKey " + TARA_KEY + " unknown for "
                        + TARA_BUSINESS + "\"}")));
        run(() -> taraMoney.collect(payment("t1", "https://shop.example/cb")));
        server.stubFor(post(urlEqualTo("/api/tara/mobilepay")).willReturn(aResponse().withStatus(500)
                .withBody(TARA_KEY)));
        run(() -> taraMoney.collect(payment("t2", "https://shop.example/cb")));
        var sent = server.findAll(postRequestedFor(urlEqualTo("/api/tara/mobilepay")));
        String callbackUrl = Json.text(Json.parse(sent.get(sent.size() - 1).getBody()), "webHookUrl");
        String callbackToken = URI.create(callbackUrl).getQuery().replaceAll(".*mm_token=", "");
        URI callback = URI.create(callbackUrl);
        run(() -> taraMoney.handleWebhook(ProviderId.TARA, WebhookRequest.builder().method("POST")
                .path(callback.getPath()).rawQuery(callback.getRawQuery())
                .body("{\"productId\":\"order-t2\",\"status\":\"SUCCESS\",\"amount\":1,\"phoneNumber\":\"670123456\"}")
                .build()));
        run(() -> taraMoney.handleWebhook(ProviderId.TARA, WebhookRequest.builder().method("POST")
                .path(callback.getPath()).rawQuery(callback.getRawQuery())
                .body("{\"productId\":\"order-t2\",\"status\":\"SUCCESS\",\"amount\":100,"
                        + "\"phoneNumber\":\"670123456\"}").build()));

        for (ILoggingEvent event : logs.list) {
            observed.add(event.getFormattedMessage());
            if (event.getArgumentArray() != null) {
                for (Object argument : event.getArgumentArray()) {
                    observed.add(String.valueOf(argument));
                }
            }
            for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause()) {
                observed.add(t.getClassName() + ": " + t.getMessage());
            }
        }

        assertThat(logs.list).isNotEmpty();
        assertThat(callbackToken).hasSize(22);
        List<String> canaries = List.of("CANARY", callbackToken);
        for (String text : observed) {
            for (String canary : canaries) {
                assertThat(text).as("must not contain a secret").doesNotContain(canary);
            }
        }
    }

    private static PaymentRequest payment(String key, String callbackUrl) {
        PaymentRequest.Builder builder = PaymentRequest.builder().idempotencyKey(key).amount(100)
                .phoneNumber("670123456").merchantReference("order-" + key).description("Order");
        if (callbackUrl != null) {
            builder.callbackUrl(URI.create(callbackUrl));
        }
        return builder.build();
    }

    /** Runs a call and records its result or exception, with the whole cause chain and stack trace. */
    private void run(Supplier<?> call) {
        try {
            observed.add(String.valueOf(call.get()));
        } catch (RuntimeException e) {
            StringWriter trace = new StringWriter();
            e.printStackTrace(new PrintWriter(trace));
            observed.add(trace.toString());
            observed.add(e.toString());
        }
    }
}
