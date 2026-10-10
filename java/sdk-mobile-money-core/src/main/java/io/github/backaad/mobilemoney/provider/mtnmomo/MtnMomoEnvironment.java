package io.github.backaad.mobilemoney.provider.mtnmomo;

import java.net.URI;

/** MTN MoMo environments. */
public enum MtnMomoEnvironment {
    /** The public sandbox, whose target environment is {@code sandbox}. */
    SANDBOX(URI.create("https://sandbox.momodeveloper.mtn.com"), "sandbox"),
    /**
     * Production. The target environment is given by MTN for each country (for Cameroon,
     * {@code mtncameroon}: to be confirmed) and must be configured.
     */
    PRODUCTION(URI.create("https://proxy.momoapi.mtn.com"), null);

    private final URI baseUrl;
    private final String targetEnvironment;

    MtnMomoEnvironment(URI baseUrl, String targetEnvironment) {
        this.baseUrl = baseUrl;
        this.targetEnvironment = targetEnvironment;
    }

    /**
     * Returns the API base URL.
     *
     * @return the base URL, without trailing slash
     */
    public URI baseUrl() {
        return baseUrl;
    }

    /**
     * Returns the default target environment.
     *
     * @return the value of {@code X-Target-Environment}, or {@code null} when it must be configured
     */
    public String defaultTargetEnvironment() {
        return targetEnvironment;
    }
}
