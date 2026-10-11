package io.github.backaad.mobilemoney.spring;

import io.github.backaad.mobilemoney.ConfigurationException;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Turns a Mobile Money configuration error into a clear "APPLICATION FAILED TO START" report. */
public final class MobileMoneyFailureAnalyzer extends AbstractFailureAnalyzer<ConfigurationException> {

    /** Created by Spring Boot. */
    public MobileMoneyFailureAnalyzer() {
    }

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, ConfigurationException cause) {
        return new FailureAnalysis("Mobile Money configuration error: " + cause.getMessage(),
                "Fix the mobile-money.* properties (see docs/spring-boot.md). Keys belong in environment variables"
                        + " or a secret store, never in a committed file.", cause);
    }
}
