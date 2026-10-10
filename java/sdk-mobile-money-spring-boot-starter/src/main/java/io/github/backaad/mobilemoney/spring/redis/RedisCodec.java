package io.github.backaad.mobilemoney.spring.redis;

import io.github.backaad.mobilemoney.ProviderId;
import io.github.backaad.mobilemoney.Transaction;
import io.github.backaad.mobilemoney.TransactionStatus;
import io.github.backaad.mobilemoney.TransactionType;
import io.github.backaad.mobilemoney.callback.ExpectedCallback;
import io.github.backaad.mobilemoney.idempotency.IdempotencyRecord;
import io.github.backaad.mobilemoney.spring.pending.PendingTransactionStore;
import java.time.Instant;
import java.util.Currency;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Explicit JSON format of what the SDK stores in Redis, independent of the Java field names. Version 1.
 * A value that cannot be read is an error, never an empty result: the stores fail closed.
 */
final class RedisCodec {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private RedisCodec() {
    }

    static String write(IdempotencyRecord record) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("v", 1);
        node.put("key", record.key());
        node.put("fingerprint", record.fingerprint());
        node.put("state", record.state().name());
        if (record.transaction() != null) {
            node.set("transaction", transaction(record.transaction()));
        }
        if (record.rejection() != null) {
            ObjectNode rejection = node.putObject("rejection");
            rejection.put("provider", record.rejection().provider().value());
            rejection.put("code", record.rejection().code());
            rejection.put("message", record.rejection().message());
        }
        node.put("owner", record.ownerToken());
        node.put("leaseUntil", record.leaseUntil().toEpochMilli());
        node.put("expiresAt", record.expiresAt().toEpochMilli());
        return MAPPER.writeValueAsString(node);
    }

    static IdempotencyRecord readIdempotencyRecord(String json) {
        JsonNode node = parse(json);
        JsonNode rejection = node.get("rejection");
        return new IdempotencyRecord(
                text(node, "key"),
                text(node, "fingerprint"),
                IdempotencyRecord.State.valueOf(text(node, "state")),
                node.has("transaction") ? transaction(node.get("transaction")) : null,
                rejection == null ? null : new IdempotencyRecord.Rejection(
                        new ProviderId(text(rejection, "provider")), optionalText(rejection, "code"),
                        text(rejection, "message")),
                text(node, "owner"),
                Instant.ofEpochMilli(number(node, "leaseUntil")),
                Instant.ofEpochMilli(number(node, "expiresAt")));
    }

    static String write(ExpectedCallback expected) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("v", 1);
        node.put("provider", expected.provider().value());
        node.put("type", expected.type().name());
        node.put("providerReference", expected.providerReference());
        node.put("merchantReference", expected.merchantReference());
        node.put("amount", expected.amount());
        node.put("currency", expected.currency().getCurrencyCode());
        node.put("phoneNumber", expected.phoneNumber());
        node.put("expiresAt", expected.expiresAt().toEpochMilli());
        return MAPPER.writeValueAsString(node);
    }

    static ExpectedCallback readExpectedCallback(String json) {
        JsonNode node = parse(json);
        return new ExpectedCallback(
                new ProviderId(text(node, "provider")),
                TransactionType.valueOf(text(node, "type")),
                optionalText(node, "providerReference"),
                text(node, "merchantReference"),
                number(node, "amount"),
                Currency.getInstance(text(node, "currency")),
                text(node, "phoneNumber"),
                Instant.ofEpochMilli(number(node, "expiresAt")));
    }

    static String write(PendingTransactionStore.Entry entry) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("v", 1);
        node.set("transaction", transaction(entry.transaction()));
        node.put("addedAt", entry.addedAt().toEpochMilli());
        return MAPPER.writeValueAsString(node);
    }

    static PendingTransactionStore.Entry readPendingEntry(String json) {
        JsonNode node = parse(json);
        return new PendingTransactionStore.Entry(transaction(node.get("transaction")),
                Instant.ofEpochMilli(number(node, "addedAt")));
    }

    private static ObjectNode transaction(Transaction t) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("provider", t.provider().value());
        node.put("type", t.type().name());
        node.put("providerReference", t.providerReference());
        node.put("merchantReference", t.merchantReference());
        node.put("amount", t.amount());
        node.put("currency", t.currency().getCurrencyCode());
        node.put("status", t.status().name());
        node.put("rawStatus", t.rawStatus());
        return node;
    }

    private static Transaction transaction(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalStateException("Stored transaction is missing");
        }
        return new Transaction(
                new ProviderId(text(node, "provider")),
                TransactionType.valueOf(text(node, "type")),
                optionalText(node, "providerReference"),
                text(node, "merchantReference"),
                number(node, "amount"),
                Currency.getInstance(text(node, "currency")),
                TransactionStatus.valueOf(text(node, "status")),
                optionalText(node, "rawStatus"));
    }

    private static JsonNode parse(String json) {
        JsonNode node = MAPPER.readTree(json);
        if (node == null || !node.isObject() || !node.has("v") || node.get("v").asInt() != 1) {
            throw new IllegalStateException("Unreadable stored value (unknown format)");
        }
        return node;
    }

    private static String text(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null) {
            throw new IllegalStateException("Stored value has no " + field);
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static long number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber()) {
            throw new IllegalStateException("Stored value has no " + field);
        }
        return value.asLong();
    }
}
