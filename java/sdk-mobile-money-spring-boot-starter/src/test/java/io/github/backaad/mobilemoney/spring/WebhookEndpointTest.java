package io.github.backaad.mobilemoney.spring;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.backaad.mobilemoney.MobileMoney;
import io.github.backaad.mobilemoney.PaymentRequest;
import io.github.backaad.mobilemoney.ProviderId;
import io.github.backaad.mobilemoney.ProviderUnavailableException;
import io.github.backaad.mobilemoney.Transaction;
import io.github.backaad.mobilemoney.TransactionStatus;
import io.github.backaad.mobilemoney.Verification;
import io.github.backaad.mobilemoney.internal.Json;
import io.github.backaad.mobilemoney.spring.event.PaymentConfirmedEvent;
import io.github.backaad.mobilemoney.spring.event.PaymentFailedEvent;
import io.github.backaad.mobilemoney.spring.event.PaymentOutcomeEvent;
import io.github.backaad.mobilemoney.spring.pending.InMemoryPendingTransactionStore;
import io.github.backaad.mobilemoney.spring.pending.PendingTransactionStore;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * The webhook endpoint: the raw request goes to {@link MobileMoney#handleWebhook}, a verified final status
 * is published as a Spring event, and the answer says nothing about the verification.
 */
class WebhookEndpointTest {

    @RegisterExtension
    static WireMockExtension tara = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(MobileMoneyAutoConfigurationTest.AUTO)
            .withUserConfiguration(Mvc.class, Events.class);

    private void run(Consumer<Ctx> test, String... properties) {
        runner.withUserConfiguration(MobileMoneyAutoConfigurationTest.CustomProvider.class)
                .withPropertyValues(properties).run(context -> {
                    assertThat(context).hasNotFailed();
                    MockMvc mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context
                            .getSourceApplicationContext()).build();
                    test.accept(new Ctx(mvc, context.getBean(Events.class).received,
                            (TestProvider) context.getBean(io.github.backaad.mobilemoney.provider.PaymentProvider.class),
                            context.getBean(MobileMoney.class), context.getBeanProvider(PendingTransactionStore.class)
                                    .getIfAvailable()));
                });
    }

    record Ctx(MockMvc mvc, List<PaymentOutcomeEvent> events, TestProvider provider, MobileMoney mobileMoney,
            PendingTransactionStore pending) {
    }

    @Test
    void verifiedSuccessIsPublishedAndAnsweredWith200() {
        run(ctx -> {
            ctx.provider().status("ref-7", TransactionStatus.SUCCESSFUL);
            perform(ctx, request(HttpMethod.POST, "/mobile-money/webhooks/test-pay?ref=ref-7")
                    .header("X-Test-Signature", "ok").content("{}"), 200);

            assertThat(ctx.events()).singleElement().isInstanceOfSatisfying(PaymentConfirmedEvent.class, e -> {
                assertThat(e.providerReference()).isEqualTo("ref-7");
                assertThat(e.provider()).isEqualTo(TestProvider.ID);
                assertThat(e.verification()).isEqualTo(Verification.STATUS_REREAD);
                assertThat(e.origin()).isEqualTo(PaymentOutcomeEvent.Origin.WEBHOOK);
            });
        });
    }

    @Test
    void failureIsPublishedAsFailed() {
        run(ctx -> {
            ctx.provider().status("ref-8", TransactionStatus.FAILED);
            perform(ctx, request(HttpMethod.PUT, "/mobile-money/webhooks/test-pay?ref=ref-8")
                    .header("X-Test-Signature", "ok"), 200);
            assertThat(ctx.events()).singleElement().isInstanceOf(PaymentFailedEvent.class);
        });
    }

    @Test
    void webhookClaimingSuccessForAPendingPaymentPublishesNothing() {
        run(ctx -> {
            perform(ctx, request(HttpMethod.POST, "/mobile-money/webhooks/test-pay?ref=ref-9")
                    .header("X-Test-Signature", "ok"), 200);
            assertThat(ctx.events()).isEmpty();
        });
    }

    @Test
    void forgedWebhookIsRefusedWithoutDetails() {
        run(ctx -> {
            ctx.provider().status("ref-7", TransactionStatus.SUCCESSFUL);
            perform(ctx, request(HttpMethod.POST, "/mobile-money/webhooks/test-pay?ref=ref-7")
                    .header("X-Test-Signature", "forged"), 400);
            assertThat(ctx.events()).isEmpty();
        });
    }

    @Test
    void unreadableStatusAsksTheProviderToRetry() {
        run(ctx -> {
            ctx.provider().status("ref-7", new ProviderUnavailableException("down", TestProvider.ID, false, null));
            perform(ctx, request(HttpMethod.POST, "/mobile-money/webhooks/test-pay?ref=ref-7")
                    .header("X-Test-Signature", "ok"), 503);
            assertThat(ctx.events()).isEmpty();
        });
    }

    @Test
    void unknownProviderAndOversizedBodyAreRefused() {
        run(ctx -> {
            perform(ctx, request(HttpMethod.POST, "/mobile-money/webhooks/campay").content("{}"), 404);
            perform(ctx, request(HttpMethod.POST, "/mobile-money/webhooks/NOT_AN_ID").content("{}"), 404);
            perform(ctx, request(HttpMethod.POST, "/mobile-money/webhooks/test-pay")
                    .header("X-Test-Signature", "ok").content(new byte[70 * 1024]), 413);
            perform(ctx, request(HttpMethod.DELETE, "/mobile-money/webhooks/test-pay"), 405);
        });
    }

    @Test
    void pathCanBeChanged() {
        run(ctx -> {
            ctx.provider().status("ref-7", TransactionStatus.SUCCESSFUL);
            perform(ctx, get("/hooks/test-pay?ref=ref-7").header("X-Test-Signature", "ok"), 200);
        }, "mobile-money.webhook.path=/hooks");
    }

    @Test
    void listenerFailureIsAnswered500WhateverItsType() {
        for (Class<?> listener : List.of(FailingListener.class, FailingWithIllegalArgument.class)) {
            runner.withUserConfiguration(MobileMoneyAutoConfigurationTest.CustomProvider.class, listener)
                    .run(context -> {
                        MockMvc mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context
                                .getSourceApplicationContext()).build();
                        context.getBean(TestProvider.class).status("ref-7", TransactionStatus.SUCCESSFUL);
                        mvc.perform(request(HttpMethod.POST, "/mobile-money/webhooks/test-pay?ref=ref-7")
                                        .header("X-Test-Signature", "ok"))
                                .andExpect(status().isInternalServerError()).andExpect(content().string(""));
                    });
        }
    }

    @Test
    void taraCallbackCanBeDeliveredAgainAfterAListenerFailure() {
        tara.stubFor(post(urlEqualTo("/api/tara/mobilepay")).willReturn(aResponse().withStatus(200)
                .withBody("{\"status\":\"SUCCESS\"}")));
        runner.withUserConfiguration(FailsOnce.class)
                .withPropertyValues("mobile-money.tara.api-key=k", "mobile-money.tara.business-id=b",
                        "mobile-money.tara.base-url=" + tara.baseUrl(),
                        "mobile-money.callback-base-url=https://shop.example/mobile-money/webhooks")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    MockMvc mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context
                            .getSourceApplicationContext()).build();
                    context.getBean(MobileMoney.class).collect(PaymentRequest.builder().idempotencyKey("k2")
                            .amount(2000).phoneNumber("655251245").merchantReference("order-2").description("d")
                            .build());
                    var sent = tara.findAll(postRequestedFor(urlEqualTo("/api/tara/mobilepay")));
                    URI callback = URI.create(Json.text(Json.parse(sent.get(sent.size() - 1).getBody()),
                            "webHookUrl"));
                    String url = callback.getPath() + "?" + callback.getRawQuery();
                    String body = "{\"productId\":\"order-2\",\"status\":\"SUCCESS\",\"amount\":2000,"
                            + "\"phoneNumber\":\"237655251245\"}";

                    mvc.perform(request(HttpMethod.POST, url).content(body))
                            .andExpect(status().isInternalServerError());
                    mvc.perform(request(HttpMethod.POST, url).content(body)).andExpect(status().isOk());
                    mvc.perform(request(HttpMethod.POST, url).content(body)).andExpect(status().isBadRequest());
                    assertThat(context.getBean(FailsOnce.class).recorded).hasSize(1);
                });
    }

    @Test
    void finalWebhookStopsFollowingThePayment() {
        run(ctx -> {
            Transaction t = ctx.mobileMoney().collect(PaymentRequest.builder().idempotencyKey("k1").amount(100)
                    .phoneNumber("670123456").merchantReference("order-1").description("d").build());
            assertThat(((InMemoryPendingTransactionStore) ctx.pending()).size()).isEqualTo(1);
            ctx.provider().status(t.providerReference(), TransactionStatus.SUCCESSFUL);

            perform(ctx, request(HttpMethod.POST, "/mobile-money/webhooks/test-pay?ref=" + t.providerReference())
                    .header("X-Test-Signature", "ok"), 200);

            assertThat(((InMemoryPendingTransactionStore) ctx.pending()).size()).isZero();
        }, "mobile-money.pending-check.enabled=true");
    }

    @Test
    void taraCallbackGoesThroughTheEndpointOnce() {
        tara.stubFor(post(urlEqualTo("/api/tara/mobilepay")).willReturn(aResponse().withStatus(200)
                .withBody("{\"status\":\"SUCCESS\"}")));
        runner.withPropertyValues("mobile-money.tara.api-key=CANARY-TARA-0001", "mobile-money.tara.business-id=b",
                "mobile-money.tara.base-url=" + tara.baseUrl(),
                "mobile-money.callback-base-url=https://shop.example/mobile-money/webhooks").run(context -> {
                    assertThat(context).hasNotFailed();
                    MockMvc mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context
                            .getSourceApplicationContext()).build();
                    context.getBean(MobileMoney.class).collect(PaymentRequest.builder().idempotencyKey("k1")
                            .amount(2000).phoneNumber("655251245").merchantReference("order-1").description("d")
                            .build());
                    var sent = tara.findAll(postRequestedFor(urlEqualTo("/api/tara/mobilepay")));
                    URI callback = URI.create(Json.text(Json.parse(sent.get(0).getBody()), "webHookUrl"));
                    assertThat(callback.getPath()).isEqualTo("/mobile-money/webhooks/tara");
                    String body = "{\"productId\":\"order-1\",\"status\":\"SUCCESS\",\"amount\":2000,"
                            + "\"phoneNumber\":\"237655251245\"}";

                    mvc.perform(request(HttpMethod.POST, callback.getPath() + "?" + callback.getRawQuery())
                            .content(body)).andExpect(status().isOk()).andExpect(content().string(""));
                    mvc.perform(request(HttpMethod.POST, callback.getPath() + "?" + callback.getRawQuery())
                            .content(body)).andExpect(status().isBadRequest());

                    assertThat(context.getBean(Events.class).received).singleElement()
                            .isInstanceOfSatisfying(PaymentConfirmedEvent.class, e -> {
                                assertThat(e.provider()).isEqualTo(ProviderId.TARA);
                                assertThat(e.verification()).isEqualTo(Verification.CALLBACK_TOKEN);
                            });
                });
    }

    private static void perform(Ctx ctx, org.springframework.test.web.servlet.RequestBuilder request, int expected) {
        try {
            ctx.mvc().perform(request).andExpect(status().is(expected)).andExpect(content().string(""));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class Mvc {
    }

    @Configuration(proxyBeanMethods = false)
    static class Events {

        final List<PaymentOutcomeEvent> received = new CopyOnWriteArrayList<>();

        @EventListener
        void on(PaymentOutcomeEvent event) {
            received.add(event);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FailingWithIllegalArgument {

        @Bean
        Object failingListener() {
            return new Object() {
                @EventListener
                void on(PaymentOutcomeEvent event) {
                    throw new IllegalArgumentException("merchant bug");
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FailsOnce {

        final List<PaymentOutcomeEvent> recorded = new CopyOnWriteArrayList<>();
        private final java.util.concurrent.atomic.AtomicBoolean failed = new java.util.concurrent.atomic.AtomicBoolean();

        @EventListener
        void on(PaymentOutcomeEvent event) {
            if (failed.compareAndSet(false, true)) {
                throw new IllegalStateException("database down");
            }
            recorded.add(event);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FailingListener {

        @Bean
        Object failingListener() {
            return new Object() {
                @EventListener
                void on(PaymentOutcomeEvent event) {
                    throw new IllegalStateException("merchant bug");
                }
            };
        }
    }
}
