package io.github.backaad.mobilemoney.spring.pending;

import io.github.backaad.mobilemoney.ProviderId;
import io.github.backaad.mobilemoney.Transaction;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Keeps pending transactions in the JVM: lost on restart, and not shared between instances. */
public final class InMemoryPendingTransactionStore implements PendingTransactionStore {

    private final Map<String, Slot> slots = new LinkedHashMap<>();

    /** Creates an empty store. */
    public InMemoryPendingTransactionStore() {
    }

    private record Slot(Entry entry, Instant nextCheckAt) {
    }

    @Override
    public synchronized void add(Transaction transaction, Instant now, Instant firstCheckAt) {
        Objects.requireNonNull(transaction.providerReference(), "providerReference");
        slots.putIfAbsent(key(transaction.provider(), transaction.providerReference()),
                new Slot(new Entry(transaction, now), firstCheckAt));
    }

    @Override
    public synchronized List<Entry> claimDue(Instant now, int limit, Instant nextCheckAt) {
        List<Map.Entry<String, Slot>> due = slots.entrySet().stream()
                .filter(e -> !e.getValue().nextCheckAt().isAfter(now))
                .sorted(Comparator.comparing(e -> e.getValue().nextCheckAt()))
                .limit(limit)
                .toList();
        List<Entry> claimed = new ArrayList<>();
        for (Map.Entry<String, Slot> e : due) {
            slots.put(e.getKey(), new Slot(e.getValue().entry(), nextCheckAt));
            claimed.add(e.getValue().entry());
        }
        return claimed;
    }

    @Override
    public synchronized void remove(ProviderId provider, String providerReference) {
        slots.remove(key(provider, providerReference));
    }

    /**
     * Returns the number of followed transactions.
     *
     * @return the number
     */
    public synchronized int size() {
        return slots.size();
    }

    static String key(ProviderId provider, String providerReference) {
        return provider.value() + ":" + providerReference;
    }
}
