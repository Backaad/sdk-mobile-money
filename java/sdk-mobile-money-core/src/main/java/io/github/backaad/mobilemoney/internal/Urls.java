package io.github.backaad.mobilemoney.internal;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** URL checks for provider configuration. */
public final class Urls {

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    private Urls() {
    }

    /**
     * Checks a provider base URL: absolute, HTTPS (plain HTTP only on the local machine), without user
     * information, query or fragment.
     *
     * @param url the URL
     * @return the URL without trailing slash
     * @throws IllegalArgumentException if the URL is not acceptable
     */
    public static URI requireSafeBaseUrl(URI url) {
        if (url == null || !url.isAbsolute() || url.getHost() == null) {
            throw new IllegalArgumentException("must be an absolute URL with a host");
        }
        String scheme = url.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(scheme.equals("http") && isLoopback(url))) {
            throw new IllegalArgumentException("must use HTTPS (plain HTTP is only allowed on localhost)");
        }
        if (url.getRawUserInfo() != null || url.getRawQuery() != null || url.getRawFragment() != null) {
            throw new IllegalArgumentException("must not contain user information, a query or a fragment");
        }
        String text = url.toString();
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return URI.create(text);
    }

    /**
     * Checks a callback URL given to a provider: absolute HTTPS (plain HTTP only on the local machine),
     * without user information.
     *
     * @param url the URL
     * @return the URL
     * @throws IllegalArgumentException if the URL is not acceptable
     */
    public static URI requireSafeCallbackUrl(URI url) {
        if (url == null || !url.isAbsolute() || url.getHost() == null) {
            throw new IllegalArgumentException("must be an absolute URL with a host");
        }
        String scheme = url.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(scheme.equals("http") && isLoopback(url))) {
            throw new IllegalArgumentException("must use HTTPS (plain HTTP is only allowed on localhost)");
        }
        if (url.getRawUserInfo() != null) {
            throw new IllegalArgumentException("must not contain user information");
        }
        return url;
    }

    private static boolean isLoopback(URI url) {
        return LOOPBACK_HOSTS.contains(url.getHost().toLowerCase(Locale.ROOT));
    }
}
