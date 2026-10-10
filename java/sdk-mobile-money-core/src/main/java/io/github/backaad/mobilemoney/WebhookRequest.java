package io.github.backaad.mobilemoney;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A webhook call exactly as received: method, path, raw query string, headers and raw body. Give it to
 * {@link MobileMoney#handleWebhook} untouched: signatures are computed over the raw content.
 *
 * <p>{@link #toString()} shows only names, never values, because they contain signatures.
 */
public final class WebhookRequest {

    /** Largest body accepted, in bytes. */
    public static final int MAX_BODY_SIZE = 64 * 1024;

    private final String method;
    private final String path;
    private final String rawQuery;
    private final Map<String, List<String>> headers;
    private final byte[] body;
    private final String remoteAddress;

    private WebhookRequest(Builder builder) {
        this.method = Objects.requireNonNull(builder.method, "method");
        this.path = builder.path == null ? "" : builder.path;
        this.rawQuery = builder.rawQuery;
        Map<String, List<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        builder.headers.forEach((name, values) -> copy.put(name, List.copyOf(values)));
        this.headers = Collections.unmodifiableMap(copy);
        this.body = builder.body == null ? new byte[0] : builder.body.clone();
        if (body.length > MAX_BODY_SIZE) {
            throw new IllegalArgumentException("Webhook body is larger than " + MAX_BODY_SIZE + " bytes");
        }
        this.remoteAddress = builder.remoteAddress;
    }

    /**
     * Starts a request.
     *
     * @return a builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the HTTP method.
     *
     * @return for example {@code "POST"}
     */
    public String method() {
        return method;
    }

    /**
     * Returns the request path.
     *
     * @return the path, possibly empty
     */
    public String path() {
        return path;
    }

    /**
     * Returns the query string as received, still URL-encoded.
     *
     * @return the query string, or empty
     */
    public Optional<String> rawQuery() {
        return Optional.ofNullable(rawQuery);
    }

    /**
     * Decodes the query string.
     *
     * @return parameter names mapped to their values, in order of appearance
     * @throws IllegalArgumentException if the query string is not correctly encoded
     */
    public Map<String, List<String>> queryParameters() {
        return decodeForm(rawQuery);
    }

    /**
     * Returns the values of a header.
     *
     * @param name header name, case-insensitive
     * @return the values, possibly empty
     */
    public List<String> headers(String name) {
        return headers.getOrDefault(name, List.of());
    }

    /**
     * Returns the first value of a header.
     *
     * @param name header name, case-insensitive
     * @return the value, or empty
     */
    public Optional<String> header(String name) {
        List<String> values = headers(name);
        return values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
    }

    /**
     * Returns a copy of the raw body.
     *
     * @return the body bytes, possibly empty
     */
    public byte[] body() {
        return body.clone();
    }

    /**
     * Returns the address of the caller, when the application provided it.
     *
     * @return the address, or empty
     */
    public Optional<String> remoteAddress() {
        return Optional.ofNullable(remoteAddress);
    }

    /**
     * Decodes an {@code application/x-www-form-urlencoded} string.
     *
     * @param encoded the string, or {@code null}
     * @return names mapped to values, in order of appearance
     * @throws IllegalArgumentException if the string is not correctly encoded
     */
    public static Map<String, List<String>> decodeForm(String encoded) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (encoded == null || encoded.isEmpty()) {
            return result;
        }
        for (String pair : encoded.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            result.computeIfAbsent(name, n -> new ArrayList<>()).add(value);
        }
        return result;
    }

    /** Shows the method, path, header names and parameter names only. */
    @Override
    public String toString() {
        List<String> parameterNames;
        try {
            parameterNames = List.copyOf(queryParameters().keySet());
        } catch (IllegalArgumentException e) {
            parameterNames = List.of("<malformed>");
        }
        return "WebhookRequest[method=" + method + ", path=" + path + ", headers=" + headers.keySet()
                + ", queryParameters=" + parameterNames + ", bodyLength=" + body.length + "]";
    }

    /** Builds a {@link WebhookRequest}. */
    public static final class Builder {

        private String method;
        private String path;
        private String rawQuery;
        private final Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        private byte[] body;
        private String remoteAddress;

        private Builder() {
        }

        /**
         * Sets the HTTP method.
         *
         * @param method for example {@code "GET"}
         * @return this builder
         */
        public Builder method(String method) {
            this.method = method;
            return this;
        }

        /**
         * Sets the request path.
         *
         * @param path the path
         * @return this builder
         */
        public Builder path(String path) {
            this.path = path;
            return this;
        }

        /**
         * Sets the query string, still URL-encoded, without the leading {@code ?}.
         *
         * @param rawQuery the query string, or {@code null}
         * @return this builder
         */
        public Builder rawQuery(String rawQuery) {
            this.rawQuery = rawQuery;
            return this;
        }

        /**
         * Adds a header value.
         *
         * @param name header name
         * @param value header value
         * @return this builder
         */
        public Builder header(String name, String value) {
            headers.computeIfAbsent(name, n -> new ArrayList<>()).add(value);
            return this;
        }

        /**
         * Sets the raw body.
         *
         * @param body the bytes, copied
         * @return this builder
         */
        public Builder body(byte[] body) {
            this.body = body == null ? null : body.clone();
            return this;
        }

        /**
         * Sets the raw body from text.
         *
         * @param body the body, encoded in UTF-8
         * @return this builder
         */
        public Builder body(String body) {
            this.body = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
            return this;
        }

        /**
         * Sets the caller's address.
         *
         * @param remoteAddress IP address of the caller
         * @return this builder
         */
        public Builder remoteAddress(String remoteAddress) {
            this.remoteAddress = remoteAddress;
            return this;
        }

        /**
         * Builds the request.
         *
         * @return the request
         * @throws IllegalArgumentException if the body is larger than {@link #MAX_BODY_SIZE}
         */
        public WebhookRequest build() {
            return new WebhookRequest(this);
        }
    }
}
