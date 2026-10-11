package io.github.backaad.mobilemoney.spring;

import io.github.backaad.mobilemoney.MobileMoney;
import io.github.backaad.mobilemoney.spring.web.WebhookController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * Registers the webhook endpoint ({@code <mobile-money.webhook.path>/<provider>}) in a Spring MVC
 * application, unless {@code mobile-money.webhook.enabled=false}.
 */
@AutoConfiguration(after = MobileMoneyAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({DispatcherServlet.class, HttpServletRequest.class})
@ConditionalOnBean(MobileMoneyWebhooks.class)
@ConditionalOnBooleanProperty(name = "mobile-money.webhook.enabled", matchIfMissing = true)
public class MobileMoneyWebMvcAutoConfiguration {

    /** Created by Spring Boot. */
    public MobileMoneyWebMvcAutoConfiguration() {
    }

    /**
     * The webhook endpoint.
     *
     * @param webhooks the webhook handler
     * @param mobileMoney the facade, for the list of providers
     * @return the endpoint
     */
    @Bean
    @ConditionalOnMissingBean
    public WebhookController mobileMoneyWebhookController(MobileMoneyWebhooks webhooks, MobileMoney mobileMoney) {
        return new WebhookController(webhooks, mobileMoney.providerIds());
    }
}
