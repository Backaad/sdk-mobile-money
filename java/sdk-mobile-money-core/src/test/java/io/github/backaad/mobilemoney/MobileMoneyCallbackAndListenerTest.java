package io.github.backaad.mobilemoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.backaad.mobilemoney.testing.StubProvider;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** The default callback URL per provider, and the listener told about new transactions. */
class MobileMoneyCallbackAndListenerTest {

    private static PaymentRequest.Builder request(String key) {
        return PaymentRequest.builder().idempotencyKey(key).amount(100).phoneNumber("670123456")
                .merchantReference("order-" + key).description("d");
    }

    @Test
    void requestWithoutCallbackUrlGetsTheUrlOfTheProviderThatProcessesIt() {
        AtomicReference<URI> seen = new AtomicReference<>();
        StubProvider primary = new StubProvider("campay").onCollect(r -> {
            throw new ProviderUnavailableException("down", ProviderId.CAMPAY, false, null);
        });
        StubProvider backup = new StubProvider("mtn-momo");
        backup.onCollect(r -> {
            seen.set(r.callbackUrl());
            return backup.accepted(r, "ref-1");
        });
        MobileMoney mobileMoney = MobileMoney.builder().provider(primary).provider(backup)
                .failover(ProviderId.MTN_MOMO)
                .callbackBaseUrl(URI.create("https://shop.example/mobile-money/webhooks")).build();

        mobileMoney.collect(request("1").build());

        assertThat(seen.get()).hasToString("https://shop.example/mobile-money/webhooks/mtn-momo");
    }

    @Test
    void callbackUrlOfTheRequestWins() {
        AtomicReference<URI> seen = new AtomicReference<>();
        StubProvider provider = new StubProvider("campay");
        provider.onCollect(r -> {
            seen.set(r.callbackUrl());
            return provider.accepted(r, "ref-1");
        });
        MobileMoney mobileMoney = MobileMoney.builder().provider(provider)
                .callbackBaseUrl(URI.create("https://shop.example/hooks")).build();

        mobileMoney.collect(request("1").callbackUrl(URI.create("https://other.example/cb")).build());

        assertThat(seen.get()).hasToString("https://other.example/cb");
    }

    @Test
    void withoutBaseUrlNothingIsAdded() {
        AtomicReference<URI> seen = new AtomicReference<>(URI.create("https://unset.example"));
        StubProvider provider = new StubProvider("campay");
        provider.onCollect(r -> {
            seen.set(r.callbackUrl());
            return provider.accepted(r, "ref-1");
        });

        MobileMoney.builder().provider(provider).build().collect(request("1").build());

        assertThat(seen.get()).isNull();
    }

    @Test
    void unsafeBaseUrlIsRefused() {
        StubProvider provider = new StubProvider("campay");
        for (String url : List.of("http://shop.example/hooks", "https://shop.example/hooks/",
                "https://shop.example/hooks?x=1", "https://user:pw@shop.example/hooks")) {
            assertThatThrownBy(() -> MobileMoney.builder().provider(provider).callbackBaseUrl(URI.create(url)).build())
                    .as(url).isInstanceOf(ConfigurationException.class);
        }
        assertThat(MobileMoney.builder().provider(provider).callbackBaseUrl(URI.create("http://localhost:8080/hooks"))
                .build()).isNotNull();
    }

    @Test
    void listenerHearsNewTransactionsOnlyOnce() {
        List<Transaction> heard = new ArrayList<>();
        StubProvider provider = new StubProvider("campay");
        MobileMoney mobileMoney = MobileMoney.builder().provider(provider).transactionListener(heard::add).build();

        Transaction first = mobileMoney.collect(request("1").build());
        mobileMoney.collect(request("1").build());

        assertThat(heard).containsExactly(first);
    }

    @Test
    void listenerHearsUnknownOutcomesButNotRefusalsOrUnsentRequests() {
        List<Transaction> heard = new ArrayList<>();
        StubProvider provider = new StubProvider("campay");
        MobileMoney mobileMoney = MobileMoney.builder().provider(provider).transactionListener(heard::add).build();

        provider.onCollect(r -> {
            throw new ProviderUnavailableException("timeout", ProviderId.CAMPAY, true, null);
        });
        Transaction unknown = mobileMoney.collect(request("1").build());
        provider.onCollect(r -> {
            throw new PaymentRejectedException("refused", ProviderId.CAMPAY, null);
        });
        assertThatThrownBy(() -> mobileMoney.collect(request("2").build()));
        provider.onCollect(r -> {
            throw new ProviderUnavailableException("down", ProviderId.CAMPAY, false, null);
        });
        assertThatThrownBy(() -> mobileMoney.collect(request("3").build()));

        assertThat(heard).containsExactly(unknown);
        assertThat(unknown.outcomeUnknown()).isTrue();
    }

    @Test
    void failingListenerNeverChangesTheResult() {
        StubProvider provider = new StubProvider("campay");
        MobileMoney mobileMoney = MobileMoney.builder().provider(provider).transactionListener(t -> {
            throw new IllegalStateException("listener bug");
        }).build();

        Transaction transaction = mobileMoney.collect(request("1").build());

        assertThat(transaction.status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(mobileMoney.collect(request("1").build())).isEqualTo(transaction);
        assertThat(provider.collects()).isEqualTo(1);
    }
}
