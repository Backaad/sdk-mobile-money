package io.github.backaad.mobilemoney.demo;

import java.util.Currency;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** What the app needs before a payment: the currency the server charges in, to show the right amount. */
@RestController
public class ConfigController {

    private final Currency currency;

    ConfigController(Currency currency) {
        this.currency = currency;
    }

    /**
     * Returns the demo's settings.
     *
     * @return for example {@code {"currency": "XAF"}}
     */
    @GetMapping("/api/config")
    Map<String, Object> config() {
        return Map.of("currency", currency.getCurrencyCode());
    }
}
