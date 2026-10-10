package io.github.backaad.mobilemoney.internal;

import io.github.backaad.mobilemoney.ProviderId;
import io.github.backaad.mobilemoney.ProviderUnavailableException;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.util.Collection;
import java.util.Currency;
import java.util.Locale;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/** Helpers shared by provider adapters to read answers safely. */
public final class Replies {

    private static final int MAX_MESSAGE_LENGTH = 200;

    private Replies() {
    }

    /**
     * Parses a response body that must be a JSON object.
     *
     * @param response the response
     * @param provider the provider, for errors
     * @param requestMayHaveBeenProcessed what to report if the body is not usable
     * @return the object
     * @throws ProviderUnavailableException if the body is empty, not JSON or not an object
     */
    public static JsonNode object(HttpResponse<byte[]> response, ProviderId provider,
            boolean requestMayHaveBeenProcessed) {
        byte[] body = response.body();
        if (body == null || body.length == 0) {
            throw new ProviderUnavailableException(
                    provider + " returned an empty response", provider, requestMayHaveBeenProcessed, null);
        }
        JsonNode json;
        try {
            json = Json.parse(body);
        } catch (JacksonException e) {
            throw new ProviderUnavailableException(
                    provider + " returned a response that is not JSON", provider, requestMayHaveBeenProcessed, null);
        }
        if (json == null || !json.isObject()) {
            throw new ProviderUnavailableException(
                    provider + " returned an unexpected response", provider, requestMayHaveBeenProcessed, null);
        }
        return json;
    }

    /**
     * Parses a body for error details, without failing.
     *
     * @param body the body
     * @return the JSON object, or {@code null}
     */
    public static JsonNode objectOrNull(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            JsonNode json = Json.parse(body);
            return json != null && json.isObject() ? json : null;
        } catch (JacksonException | IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Makes a provider text safe to show: secrets replaced by {@code ***} first, then control characters
     * removed and the length limited. Redaction comes first: a secret cut in half would no longer match.
     *
     * @param text the text, or {@code null}
     * @param secrets values that must never appear
     * @return the safe text, or {@code null} if nothing is left
     */
    public static String safeText(String text, Collection<String> secrets) {
        if (text == null) {
            return null;
        }
        String redacted = text;
        for (String secret : secrets) {
            if (secret != null && secret.length() >= 4) {
                redacted = redacted.replace(secret, "***");
            }
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < redacted.length() && out.length() < MAX_MESSAGE_LENGTH; i++) {
            char c = redacted.charAt(i);
            out.append(Character.isISOControl(c) ? ' ' : c);
        }
        String result = out.toString().trim();
        return result.isEmpty() ? null : result;
    }

    /**
     * Reads a whole amount given as a number or a numeric text.
     *
     * @param node the value
     * @return the amount, or {@code null} if absent, not numeric or not whole
     */
    public static Long wholeAmount(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            BigDecimal value = node.isNumber() ? node.decimalValue()
                    : node.isString() ? new BigDecimal(node.asString().trim()) : null;
            return value == null ? null : value.longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            return null;
        }
    }

    /**
     * Reads a currency code.
     *
     * @param code the ISO 4217 code, or {@code null}
     * @return the currency, or {@code null} if absent or unknown
     */
    public static Currency currency(String code) {
        if (code == null) {
            return null;
        }
        try {
            return Currency.getInstance(code.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Returns {@code null} for a blank text.
     *
     * @param value the text
     * @return the text, or {@code null} if blank
     */
    public static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
