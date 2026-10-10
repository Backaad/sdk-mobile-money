package io.github.backaad.mobilemoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.backaad.mobilemoney.phone.PhoneNumber;
import io.github.backaad.mobilemoney.phone.ProviderPhoneFormat;
import io.github.backaad.mobilemoney.testing.TestData;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ModelTest {

    private static PaymentRequest.Builder payment() {
        return PaymentRequest.builder().idempotencyKey("order-1").amount(100).phoneNumber("670123456")
                .merchantReference("order-1").description("Order 1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "with space", "line\nbreak", "tab\tkey", "slash/key", "accentué"})
    void idempotencyKeysAreRestricted(String key) {
        assertThatThrownBy(() -> payment().idempotencyKey(key).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotencyKey");
    }

    @Test
    void idempotencyKeyAcceptsUsualIdentifiers() {
        assertThat(payment().idempotencyKey("order-1234:attempt_1.v2").build()).isNotNull();
        assertThatThrownBy(() -> payment().idempotencyKey("k".repeat(256)).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void amountsArePositiveIntegers() {
        assertThatThrownBy(() -> payment().amount(0).build()).hasMessageContaining("amount");
        assertThatThrownBy(() -> payment().amount(-5).build()).hasMessageContaining("amount");
    }

    @Test
    void textsAreRequiredAndFreeOfControlCharacters() {
        assertThatThrownBy(() -> payment().merchantReference(" ").build()).hasMessageContaining("merchantReference");
        assertThatThrownBy(() -> payment().description("a\u0000b").build()).hasMessageContaining("control");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/relative", "http://merchant.example/hook", "https://user:pw@merchant.example/hook"})
    void callbackUrlsMustBeSafe(String url) {
        assertThatThrownBy(() -> payment().callbackUrl(URI.create(url)).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("callbackUrl");
    }

    @Test
    void callbackUrlsCanBeHttpsOrLocal() {
        assertThat(payment().callbackUrl(URI.create("https://merchant.example/hook")).build()).isNotNull();
        assertThat(payment().callbackUrl(URI.create("http://localhost:8080/hook")).build()).isNotNull();
    }

    @Test
    void requestsDoNotPrintTheFullPhoneNumberOrName() {
        assertThat(payment().build().toString()).doesNotContain("670123456").contains("+2376******56");
        PayoutRequest payout = PayoutRequest.builder().idempotencyKey("k").amount(1).phoneNumber("690123456")
                .beneficiaryName("Jane Doe").merchantReference("p").description("d").build();
        assertThat(payout.toString()).doesNotContain("690123456").doesNotContain("Jane Doe");
    }

    @Test
    void webhookBodiesAreLimited() {
        assertThatThrownBy(() -> WebhookRequest.builder().method("POST")
                .body(new byte[WebhookRequest.MAX_BODY_SIZE + 1]).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void webhookRequestIsImmutable() {
        byte[] body = "{\"a\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        WebhookRequest request = WebhookRequest.builder().method("POST").header("X-Test", "1").body(body).build();
        body[0] = 'X';
        request.body()[1] = 'Y';

        assertThat(new String(request.body(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("{\"a\":1}");
        assertThat(request.header("x-test")).contains("1");
    }

    /** Rule 6: Campay's format applied to every reference number of the shared fixture. */
    @Test
    void everyProviderFormatForEveryReferenceNumber() {
        TestData.read("phone-numbers.json").get("valid").valueStream().forEach(c -> {
            PhoneNumber number = PhoneNumber.parse(c.get("input").asString());
            for (ProviderPhoneFormat format : ProviderPhoneFormat.values()) {
                assertThat(format.format(number)).as(format.name())
                        .isEqualTo("237" + c.get("national").asString())
                        .matches("2376[0-9]{8}");
            }
        });
    }

    @Test
    void everyRealProviderHasItsPhoneFormat() {
        assertThat(java.util.Arrays.stream(ProviderPhoneFormat.values()).map(ProviderPhoneFormat::provider))
                .containsExactlyInAnyOrder(ProviderId.CAMPAY, ProviderId.MTN_MOMO, ProviderId.NOTCHPAY,
                        ProviderId.TARA);
    }
}
