package io.github.backaad.mobilemoney.provider.campay;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.REFERENCE;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.TOKEN;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.fixture;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.okJson;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.stubToken;
import static io.github.backaad.mobilemoney.testing.CampayTestServer.tokenRequest;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.backaad.mobilemoney.ProviderId;
import io.github.backaad.mobilemoney.internal.HttpCaller;
import io.github.backaad.mobilemoney.testing.CampayTestServer;
import io.github.backaad.mobilemoney.testing.MutableClock;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Rule 5: access tokens are kept until they expire, then renewed, including after a 401. */
class CampayTokenCacheTest {

    @RegisterExtension
    static WireMockExtension campay = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private final MutableClock clock = MutableClock.at("2026-10-10T12:00:00Z");
    private CampayProvider provider;

    @BeforeEach
    void setUp() {
        campay.stubFor(get(urlEqualTo("/api/transaction/" + REFERENCE + "/"))
                .willReturn(okJson(fixture("transaction-pending.json"))));
        provider = CampayTestServer.provider(campay, clock).build();
    }

    private void readStatus() {
        provider.fetchStatus(null, REFERENCE);
    }

    @Test
    void tokenIsReusedUntilOneMinuteBeforeItExpires() {
        stubToken(campay, TOKEN, 3600);

        readStatus();
        readStatus();
        clock.advance(Duration.ofSeconds(3600 - 61));
        readStatus();
        campay.verify(1, postRequestedFor(urlEqualTo("/api/token/")));

        clock.advance(Duration.ofSeconds(1));
        readStatus();
        campay.verify(2, postRequestedFor(urlEqualTo("/api/token/")));
    }

    @Test
    void shortLivedTokensKeepHalfTheirLifetimeAsMargin() {
        stubToken(campay, TOKEN, 30);

        readStatus();
        clock.advance(Duration.ofSeconds(14));
        readStatus();
        campay.verify(1, postRequestedFor(urlEqualTo("/api/token/")));

        clock.advance(Duration.ofSeconds(1));
        readStatus();
        campay.verify(2, postRequestedFor(urlEqualTo("/api/token/")));
    }

    @Test
    void missingExpirationUsesAShortDefault() {
        campay.stubFor(tokenRequest().willReturn(okJson("{\"token\": \"" + TOKEN + "\"}")));

        readStatus();
        clock.advance(CampayTokens.DEFAULT_LIFETIME.minusSeconds(1));
        readStatus();
        campay.verify(1, postRequestedFor(urlEqualTo("/api/token/")));

        clock.advance(Duration.ofSeconds(1));
        readStatus();
        campay.verify(2, postRequestedFor(urlEqualTo("/api/token/")));
    }

    @Test
    void concurrentCallersShareOneTokenRequest() throws Exception {
        campay.stubFor(tokenRequest().willReturn(
                okJson("{\"token\": \"" + TOKEN + "\", \"expires_in\": 3600}").withFixedDelay(300)));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Object>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                calls.add(() -> {
                    readStatus();
                    return null;
                });
            }
            for (Future<Object> f : pool.invokeAll(calls)) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        campay.verify(1, postRequestedFor(urlEqualTo("/api/token/")));
    }

    @Test
    void invalidationOnlyDropsTheTokenThatWasRefused() {
        stubToken(campay, TOKEN, 3600);
        CampayTokens tokens = new CampayTokens(new HttpCaller(HttpClient.newHttpClient(), ProviderId.CAMPAY),
                URI.create(campay.baseUrl() + "/api/token/"), CampayTestServer.USERNAME, CampayTestServer.PASSWORD,
                clock, Duration.ofSeconds(2));

        String first = tokens.get();
        tokens.invalidate("a-token-refused-earlier-by-another-thread");
        assertThat(tokens.get()).isEqualTo(first);
        campay.verify(1, postRequestedFor(urlEqualTo("/api/token/")));

        tokens.invalidate(first);
        tokens.get();
        campay.verify(2, postRequestedFor(urlEqualTo("/api/token/")));
    }

    @Test
    void failedRenewalIsNotCached() {
        campay.stubFor(tokenRequest().willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                .withStatus(503)));
        try {
            readStatus();
        } catch (RuntimeException expected) {
            // The provider is unavailable.
        }
        stubToken(campay, TOKEN, 3600);

        readStatus();

        campay.verify(2, postRequestedFor(urlEqualTo("/api/token/")));
    }
}
