package io.github.backaad.mobilemoney.testing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.backaad.mobilemoney.provider.campay.CampayEnvironment;
import io.github.backaad.mobilemoney.provider.campay.CampayProvider;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;

/**
 * Helpers to run Campay against WireMock. The credentials are distinctive fake values ("canaries"), so
 * that tests can check they never appear in logs, messages or {@code toString()}.
 */
public final class CampayTestServer {

    public static final String USERNAME = "CANARY-USERNAME-1f2e";
    public static final String PASSWORD = "CANARY-PASSWORD-9a8b";
    public static final String WEBHOOK_KEY = "CANARY-WEBHOOK-KEY-7c6d";
    public static final String TOKEN = "CANARY-TOKEN-5e4f";
    public static final String TOKEN_2 = "CANARY-TOKEN-RENEWED-3d2c";
    public static final String REFERENCE = "bcedde9b-62a7-4421-96ac-2e6179552a1a";

    private CampayTestServer() {
    }

    public static CampayProvider.Builder provider(WireMockExtension server, Clock clock) {
        return CampayProvider.builder()
                .environment(CampayEnvironment.SANDBOX)
                .baseUrl(URI.create(server.baseUrl()))
                .username(USERNAME)
                .password(PASSWORD)
                .webhookKey(WEBHOOK_KEY)
                .requestTimeout(Duration.ofMillis(800))
                .clock(clock);
    }

    /** Stubs the token endpoint: it only answers to the right credentials. */
    public static void stubToken(WireMockExtension server, String token, long expiresIn) {
        server.stubFor(tokenRequest().willReturn(okJson(
                "{\"token\": \"" + token + "\", \"expires_in\": " + expiresIn + "}")));
    }

    public static MappingBuilder tokenRequest() {
        return post(urlEqualTo("/api/token/"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson(
                        "{\"username\": \"" + USERNAME + "\", \"password\": \"" + PASSWORD + "\"}"));
    }

    public static ResponseDefinitionBuilder okJson(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
    }

    public static ResponseDefinitionBuilder json(int status, String body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
    }

    /** Reads a fixture from src/test/resources/campay. */
    public static String fixture(String name) {
        try (InputStream in = CampayTestServer.class.getResourceAsStream("/campay/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
