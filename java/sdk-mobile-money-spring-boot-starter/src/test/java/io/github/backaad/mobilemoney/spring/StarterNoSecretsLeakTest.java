package io.github.backaad.mobilemoney.spring;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Rule 7 for the starter: keys set in mobile-money.* never appear in logs, startup failure reports or
 * {@code toString()}.
 */
class StarterNoSecretsLeakTest {

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final List<String> observed = new ArrayList<>();
    private Logger root;

    @BeforeEach
    void capture() {
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        logs.start();
        root.addAppender(logs);
    }

    @AfterEach
    void release() {
        root.detachAppender(logs);
    }

    @Test
    void keysNeverLeak() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(MobileMoneyAutoConfigurationTest.AUTO);
        String[] all = {"mobile-money.default-provider=campay",
            "mobile-money.callback-base-url=https://shop.example/mobile-money/webhooks",
            "mobile-money.campay.environment=sandbox", "mobile-money.campay.username=CANARY-U-1",
            "mobile-money.campay.password=CANARY-P-2", "mobile-money.campay.webhook-key=CANARY-W-3",
            "mobile-money.mtn-momo.environment=sandbox", "mobile-money.mtn-momo.collection.subscription-key=CANARY-S-4",
            "mobile-money.mtn-momo.collection.api-user=CANARY-A-5", "mobile-money.mtn-momo.collection.api-key=CANARY-K-6",
            "mobile-money.notchpay.public-key=CANARY-N-7", "mobile-money.notchpay.private-key=CANARY-N-8",
            "mobile-money.notchpay.webhook-secret=CANARY-N-9", "mobile-money.tara.api-key=CANARY-T-10",
            "mobile-money.tara.business-id=CANARY-T-11"};
        runner.withPropertyValues(all).run(context -> {
            assertThat(context).hasNotFailed();
            observed.add(context.getBean(MobileMoneyProperties.class).toString());
            context.getBean(io.github.backaad.mobilemoney.MobileMoney.class).providerIds().forEach(id ->
                    observed.add(context.getBean(io.github.backaad.mobilemoney.MobileMoney.class).provider(id)
                            .toString()));
        });
        // Failures, with keys set next to the missing or wrong setting.
        runner.withPropertyValues("mobile-money.campay.password=CANARY-P-12", "mobile-money.campay.username=CANARY-U-13")
                .run(context -> record(context.getStartupFailure()));
        runner.withPropertyValues("mobile-money.mtn-momo.environment=production",
                "mobile-money.mtn-momo.collection.subscription-key=CANARY-S-14",
                "mobile-money.mtn-momo.collection.api-user=CANARY-A-15",
                "mobile-money.mtn-momo.collection.api-key=CANARY-K-16").run(context -> record(context.getStartupFailure()));
        runner.withPropertyValues("mobile-money.simulation.enabled=true", "mobile-money.notchpay.public-key=CANARY-N-17")
                .run(context -> record(context.getStartupFailure()));

        for (ILoggingEvent event : logs.list) {
            observed.add(event.getFormattedMessage());
            for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause()) {
                observed.add(t.getMessage());
            }
        }
        assertThat(logs.list).isNotEmpty();
        assertThat(observed).allSatisfy(text -> assertThat(String.valueOf(text)).doesNotContain("CANARY"));
    }

    private void record(Throwable failure) {
        assertThat(failure).isNotNull();
        StringWriter trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        observed.add(trace.toString());
        observed.add(new MobileMoneyFailureAnalyzer().analyze(failure).getDescription());
    }
}
