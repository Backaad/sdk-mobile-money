package io.github.backaad.mobilemoney.provider.campay;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.REFERENCE;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.WEBHOOK_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.backaad.mobilemoney.ConfigurationException;
import io.github.backaad.mobilemoney.TransactionType;
import io.github.backaad.mobilemoney.WebhookRequest;
import io.github.backaad.mobilemoney.WebhookVerificationException;
import io.github.backaad.mobilemoney.provider.WebhookNotification;
import io.github.backaad.mobilemoney.testing.CampayTestServer;
import io.github.backaad.mobilemoney.testing.MutableClock;
import io.github.backaad.mobilemoney.testing.TestJwt;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Webhook authentication and parsing for Campay (GET or POST, JWT HS256 signed with the webhook key). */
class CampayWebhookTest {

    @RegisterExtension
    static WireMockExtension campay = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    // 2026-10-10T12:00:00Z
    private static final long NOW = 1791633600L;

    private final MutableClock clock = MutableClock.at("2026-10-10T12:00:00Z");
    private final CampayProvider provider = CampayTestServer.provider(campay, clock).build();

    static String validSignature() {
        return TestJwt.hs256("{\"iat\":" + NOW + ",\"exp\":" + (NOW + 300) + "}", WEBHOOK_KEY);
    }

    static String query(String signature, String extra) {
        return "status=SUCCESSFUL&reference=" + REFERENCE + "&amount=100&currency=XAF&operator=MTN"
                + "&code=CP201027T00005&operator_reference=1880106956&external_reference=order-1234"
                + "&signature=" + URLEncoder.encode(signature, StandardCharsets.UTF_8) + extra;
    }

    static WebhookRequest get(String rawQuery) {
        return WebhookRequest.builder().method("GET").path("/webhooks/campay").rawQuery(rawQuery).build();
    }

    @Test
    void acceptsASignedGetWebhook() {
        WebhookNotification notification = provider.parseWebhook(get(query(validSignature(), "")));

        assertThat(notification.providerReference()).isEqualTo(REFERENCE);
        assertThat(notification.merchantReference()).isEqualTo("order-1234");
        assertThat(notification.claimedRawStatus()).isEqualTo("SUCCESSFUL");
        assertThat(notification.type()).isNull();
    }

    @Test
    void acceptsASignedJsonPost() {
        WebhookRequest request = WebhookRequest.builder().method("POST")
                .header("Content-Type", "application/json")
                .body("{\"status\": \"FAILED\", \"reference\": \"" + REFERENCE + "\", \"endpoint\": \"withdraw\","
                        + " \"amount\": 100, \"external_reference\": \"payout-77\", \"signature\": \""
                        + validSignature() + "\"}")
                .build();

        WebhookNotification notification = provider.parseWebhook(request);

        assertThat(notification.providerReference()).isEqualTo(REFERENCE);
        assertThat(notification.type()).isEqualTo(TransactionType.PAYOUT);
        assertThat(notification.claimedRawStatus()).isEqualTo("FAILED");
    }

    @Test
    void acceptsASignedFormPost() {
        WebhookRequest request = WebhookRequest.builder().method("POST")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body(query(validSignature(), ""))
                .build();

        assertThat(provider.parseWebhook(request).providerReference()).isEqualTo(REFERENCE);
    }

    @Test
    void rejectsAMissingSignature() {
        assertRejected(get("status=SUCCESSFUL&reference=" + REFERENCE), "Unsigned webhook");
        assertRejected(get(query("", "")), "Unsigned webhook");
    }

    @Test
    void rejectsUnsignedTokens() {
        assertRejected(get(query(TestJwt.unsigned("{\"exp\":" + (NOW + 300) + "}"), "")),
                "Invalid webhook signature: unsigned token");
    }

    @Test
    void rejectsOtherAlgorithms() {
        String hs512 = TestJwt.hs256("{\"alg\":\"HS512\"}", "{\"exp\":" + (NOW + 300) + "}", WEBHOOK_KEY);

        assertRejected(get(query(hs512, "")), "Invalid webhook signature: unsupported algorithm");
    }

    @Test
    void rejectsATokenSignedWithAnotherKey() {
        String other = TestJwt.hs256("{\"exp\":" + (NOW + 300) + "}", "another-application-key");

        assertRejected(get(query(other, "")), "Invalid webhook signature: invalid signature");
    }

    @Test
    void rejectsExpiredTokens() {
        String expired = TestJwt.hs256("{\"exp\":" + (NOW - 120) + "}", WEBHOOK_KEY);

        assertRejected(get(query(expired, "")), "Invalid webhook signature: expired token");
    }

    @Test
    void acceptsATokenWithoutExpirationBecauseCampayMayNotSendOne() {
        // Decision recorded in docs/providers/campay.md: exp is checked when present. Every field of the
        // event comes from a new status read, so a replayed signature cannot confirm anything by itself.
        String noExp = TestJwt.hs256("{}", WEBHOOK_KEY);

        assertThat(provider.parseWebhook(get(query(noExp, ""))).providerReference()).isEqualTo(REFERENCE);
    }

    @Test
    void rejectsSignedClaimsThatContradictTheParameters() {
        String signedForAnother = TestJwt.hs256(
                "{\"reference\":\"11111111-2222-3333-4444-555555555555\",\"exp\":" + (NOW + 300) + "}", WEBHOOK_KEY);

        assertRejected(get(query(signedForAnother, "")), "Webhook parameter 'reference' does not match its signature");
    }

    @Test
    void rejectsDuplicatedParameters() {
        assertRejected(get(query(validSignature(), "&reference=11111111-2222-3333-4444-555555555555")),
                "Webhook parameter 'reference' is given more than once");
        assertRejected(get(query(validSignature(), "&signature=x")),
                "Webhook parameter 'signature' is given more than once");
    }

    @Test
    void rejectsTheSameParameterInQueryAndBody() {
        WebhookRequest request = WebhookRequest.builder().method("POST")
                .rawQuery("reference=11111111-2222-3333-4444-555555555555")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body(query(validSignature(), ""))
                .build();

        assertRejected(request, "Webhook parameter 'reference' is given more than once");
    }

    @Test
    void rejectsInvalidReferences() {
        assertRejected(get(query(validSignature(), "").replace(REFERENCE, "..%2Fbalance")),
                "Webhook without a valid reference");
    }

    @Test
    void rejectsOtherMethodsAndMalformedInput() {
        assertRejected(WebhookRequest.builder().method("PUT").rawQuery(query(validSignature(), "")).build(),
                "Unexpected webhook method");
        assertRejected(get("signature=%zz"), "Malformed webhook");
        assertRejected(WebhookRequest.builder().method("POST").header("Content-Type", "application/json")
                .body("{\"signature\": {\"nested\": 1}}").build(), "Malformed webhook");
    }

    @Test
    void refusesWebhooksWhenNoKeyIsConfigured() {
        CampayProvider withoutKey = CampayTestServer.provider(campay, clock).webhookKey(null).build();

        assertThatThrownBy(() -> withoutKey.parseWebhook(get(query(validSignature(), ""))))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("webhook key is not configured");
    }

    @Test
    void webhookRequestToStringHidesValues() {
        WebhookRequest request = get(query(validSignature(), ""));

        assertThat(request.toString()).contains("signature").doesNotContain(validSignature().substring(0, 20))
                .doesNotContain(REFERENCE);
    }

    private void assertRejected(WebhookRequest request, String message) {
        assertThatThrownBy(() -> provider.parseWebhook(request))
                .isInstanceOf(WebhookVerificationException.class)
                .hasMessage(message);
    }
}
