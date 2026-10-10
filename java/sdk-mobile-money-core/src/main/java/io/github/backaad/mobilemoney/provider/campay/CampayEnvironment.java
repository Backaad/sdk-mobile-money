package io.github.backaad.mobilemoney.provider.campay;

import java.net.URI;

/** Campay environments. */
public enum CampayEnvironment {
    /** Sandbox, with its own accounts and keys. */
    SANDBOX(URI.create("https://demo.campay.net")),
    /** Production. */
    PRODUCTION(URI.create("https://www.campay.net"));

    private final URI baseUrl;

    CampayEnvironment(URI baseUrl) {
        this.baseUrl = baseUrl;
    }

    /**
     * Returns the API base URL.
     *
     * @return the base URL, without trailing slash
     */
    public URI baseUrl() {
        return baseUrl;
    }
}
