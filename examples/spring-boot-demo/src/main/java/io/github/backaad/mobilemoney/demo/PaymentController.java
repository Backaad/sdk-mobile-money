package io.github.backaad.mobilemoney.demo;

import io.github.backaad.mobilemoney.Currencies;
import io.github.backaad.mobilemoney.IdempotencyKeyConflictException;
import io.github.backaad.mobilemoney.InvalidPhoneNumberException;
import io.github.backaad.mobilemoney.MobileMoney;
import io.github.backaad.mobilemoney.MobileMoneyException;
import io.github.backaad.mobilemoney.PaymentRejectedException;
import io.github.backaad.mobilemoney.PaymentRequest;
import io.github.backaad.mobilemoney.ProviderUnavailableException;
import io.github.backaad.mobilemoney.RequestInProgressException;
import io.github.backaad.mobilemoney.Transaction;
import io.github.backaad.mobilemoney.TransactionStatus;
import io.github.backaad.mobilemoney.UnsupportedCapabilityException;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two routes the app calls. The third route, the webhook endpoint
 * ({@code /mobile-money/webhooks/<provider>}), comes with the starter.
 *
 * <p><strong>Demo only.</strong> These routes have no authentication, and the app sends the amount. A real
 * server authenticates its users and computes the amount from the order on the server side: never trust an
 * amount sent by an app.
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentController.class);
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9-]{8,64}");
    private static final long MAX_AMOUNT = 1_000_000;

    private final MobileMoney mobileMoney;
    private final Payments payments;
    private final Currency currency;

    PaymentController(MobileMoney mobileMoney, Payments payments, @Value("${demo.currency:}") String currency) {
        this.mobileMoney = mobileMoney;
        this.payments = payments;
        this.currency = currency == null || currency.isBlank() ? Currencies.XAF
                : Currency.getInstance(currency.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * What the app sends to start a payment.
     *
     * @param phoneNumber the customer's number, in any usual written form
     * @param amount the amount, in the demo's currency (XAF by default)
     * @param description shown to the customer, optional
     */
    record StartPayment(String phoneNumber, Long amount, String description) {
    }

    /**
     * Starts a payment. The app generates one {@code Idempotency-Key} per payment attempt and sends it again
     * if it retries: the customer is asked to pay only once.
     *
     * @param idempotencyKey a unique key per attempt, for example a UUID
     * @param body the payment
     * @return the payment, usually {@code PENDING}: the customer confirms on the phone
     */
    @PostMapping
    ResponseEntity<Map<String, Object>> start(@RequestHeader(name = "Idempotency-Key", required = false)
            String idempotencyKey, @RequestBody StartPayment body) {
        if (idempotencyKey == null || !KEY.matcher(idempotencyKey).matches()) {
            return error(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
                    "Send an Idempotency-Key header (8 to 64 letters, digits or dashes), the same for retries");
        }
        if (body == null || body.amount() == null || body.amount() <= 0 || body.amount() > MAX_AMOUNT) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT", "amount must be between 1 and " + MAX_AMOUNT);
        }
        String reference = "demo-" + idempotencyKey;
        PaymentRequest request;
        try {
            request = PaymentRequest.builder()
                    .idempotencyKey(idempotencyKey)
                    .amount(body.amount())
                    .currency(currency)
                    .phoneNumber(body.phoneNumber())
                    .merchantReference(reference)
                    .description(body.description() == null || body.description().isBlank()
                            ? "Demo payment" : body.description())
                    .build();
        } catch (InvalidPhoneNumberException e) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_PHONE_NUMBER", e.reason().name());
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage());
        }
        try {
            Transaction transaction = mobileMoney.collect(request);
            payments.save(transaction);
            return ResponseEntity.status(HttpStatus.CREATED).body(view(transaction));
        } catch (PaymentRejectedException e) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, "PAYMENT_REJECTED", e.getMessage());
        } catch (ProviderUnavailableException e) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_UNAVAILABLE",
                    "Nothing was sent: retry with the same Idempotency-Key");
        } catch (IdempotencyKeyConflictException e) {
            return error(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "This key was used for another payment");
        } catch (RequestInProgressException e) {
            return error(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS", "This payment is still being sent: retry later");
        } catch (UnsupportedCapabilityException e) {
            return error(HttpStatus.BAD_REQUEST, "UNSUPPORTED", e.getMessage());
        } catch (MobileMoneyException e) {
            LOG.warn("Payment {} failed: {}", reference, e.getClass().getSimpleName());
            return error(HttpStatus.SERVICE_UNAVAILABLE, "UNAVAILABLE", "Retry with the same Idempotency-Key");
        }
    }

    /**
     * Reads a payment, asking the provider when it is still pending.
     *
     * @param reference the merchant reference returned when the payment started
     * @return the payment
     */
    @GetMapping("/{reference}")
    ResponseEntity<Map<String, Object>> status(@PathVariable("reference") String reference) {
        Transaction known = payments.find(reference).orElse(null);
        if (known == null) {
            return error(HttpStatus.NOT_FOUND, "UNKNOWN_PAYMENT", "No payment with this reference");
        }
        if (known.status() == TransactionStatus.PENDING && !known.outcomeUnknown()
                && mobileMoney.provider(known.provider()).capabilities().statusQuery()) {
            try {
                Transaction current = mobileMoney.status(known);
                payments.save(current);
                known = payments.find(reference).orElse(current);
            } catch (MobileMoneyException e) {
                // Transient: the payment stays pending, the app asks again later.
                LOG.debug("Status of {} not read now: {}", reference, e.getClass().getSimpleName());
            }
        }
        return ResponseEntity.ok(view(known));
    }

    private static Map<String, Object> view(Transaction transaction) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("reference", transaction.merchantReference());
        view.put("status", transaction.status().name());
        view.put("provider", transaction.provider().value());
        view.put("amount", transaction.amount());
        view.put("currency", transaction.currency().getCurrencyCode());
        // The answer was lost: the payment is never sent again, its outcome comes with the webhook.
        view.put("outcomeUnknown", transaction.outcomeUnknown());
        return view;
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
