package io.github.backaad.mobilemoney.demo;

import io.github.backaad.mobilemoney.Currencies;
import java.util.Currency;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/** Demo server: start a payment, read its status, receive the providers' webhooks. */
@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    /**
     * The currency of the demo's payments.
     *
     * @param currency the {@code demo.currency} property: empty for XAF, EUR for the MTN MoMo public sandbox
     * @return the currency
     */
    @Bean
    Currency demoCurrency(@Value("${demo.currency:}") String currency) {
        return currency == null || currency.isBlank() ? Currencies.XAF
                : Currency.getInstance(currency.trim().toUpperCase(Locale.ROOT));
    }
}
