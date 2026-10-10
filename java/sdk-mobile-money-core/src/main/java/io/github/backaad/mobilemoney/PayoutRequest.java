package io.github.backaad.mobilemoney;

import io.github.backaad.mobilemoney.internal.Checks;
import io.github.backaad.mobilemoney.internal.Urls;
import io.github.backaad.mobilemoney.phone.PhoneNumber;
import java.net.URI;
import java.util.Currency;
import java.util.Objects;

/**
 * A request to pay money out to a beneficiary.
 *
 * @param idempotencyKey key that identifies this request for 24 hours: sending the same request again
 *     with the same key returns the first result instead of paying twice. Required; 1 to 255
 *     characters among letters, digits and {@code . _ : -}
 * @param amount amount in whole units of the currency, greater than zero
 * @param currency currency, for example {@link Currencies#XAF}
 * @param phoneNumber beneficiary's Mobile Money number
 * @param beneficiaryName beneficiary's name; sent to the providers that take it
 * @param merchantReference the merchant's own reference
 * @param description short description
 * @param callbackUrl URL the provider calls back, for providers that take one per request, or
 *     {@code null}
 */
public record PayoutRequest(
        String idempotencyKey,
        long amount,
        Currency currency,
        PhoneNumber phoneNumber,
        String beneficiaryName,
        String merchantReference,
        String description,
        URI callbackUrl) {

    /** Validates the fields. */
    public PayoutRequest {
        Checks.requireIdempotencyKey(idempotencyKey);
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be greater than zero");
        }
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(phoneNumber, "phoneNumber");
        Checks.requireText(beneficiaryName, "beneficiaryName", PaymentRequest.MAX_TEXT_LENGTH);
        Checks.requireText(merchantReference, "merchantReference", PaymentRequest.MAX_TEXT_LENGTH);
        Checks.requireText(description, "description", PaymentRequest.MAX_TEXT_LENGTH);
        if (callbackUrl != null) {
            try {
                Urls.requireSafeCallbackUrl(callbackUrl);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("callbackUrl " + e.getMessage(), e);
            }
        }
    }

    /**
     * Starts a request. The currency defaults to {@link Currencies#XAF}.
     *
     * @return a builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Masks the phone number and leaves out the beneficiary's name. */
    @Override
    public String toString() {
        return "PayoutRequest[idempotencyKey=" + idempotencyKey + ", amount=" + amount + ", currency=" + currency
                + ", phoneNumber=" + phoneNumber + ", merchantReference="
                + merchantReference + "]";
    }

    /** Builds a {@link PayoutRequest}. */
    public static final class Builder {

        private String idempotencyKey;
        private long amount;
        private Currency currency = Currencies.XAF;
        private PhoneNumber phoneNumber;
        private String beneficiaryName;
        private String merchantReference;
        private String description;
        private URI callbackUrl;

        private Builder() {
        }

        /**
         * Sets the idempotency key.
         *
         * @param idempotencyKey see {@link PayoutRequest#idempotencyKey()}
         * @return this builder
         */
        public Builder idempotencyKey(String idempotencyKey) {
            this.idempotencyKey = idempotencyKey;
            return this;
        }

        /**
         * Sets the amount.
         *
         * @param amount whole units, greater than zero
         * @return this builder
         */
        public Builder amount(long amount) {
            this.amount = amount;
            return this;
        }

        /**
         * Sets the currency.
         *
         * @param currency the currency
         * @return this builder
         */
        public Builder currency(Currency currency) {
            this.currency = currency;
            return this;
        }

        /**
         * Sets the beneficiary's number.
         *
         * @param phoneNumber a normalized number
         * @return this builder
         */
        public Builder phoneNumber(PhoneNumber phoneNumber) {
            this.phoneNumber = phoneNumber;
            return this;
        }

        /**
         * Parses and sets the beneficiary's number.
         *
         * @param phoneNumber the number in any common written form
         * @return this builder
         * @throws InvalidPhoneNumberException if the number is not valid
         */
        public Builder phoneNumber(String phoneNumber) {
            this.phoneNumber = PhoneNumber.parse(phoneNumber);
            return this;
        }


        /**
         * Sets the beneficiary's name.
         *
         * @param beneficiaryName the name
         * @return this builder
         */
        public Builder beneficiaryName(String beneficiaryName) {
            this.beneficiaryName = beneficiaryName;
            return this;
        }

        /**
         * Sets the merchant reference.
         *
         * @param merchantReference the merchant's reference
         * @return this builder
         */
        public Builder merchantReference(String merchantReference) {
            this.merchantReference = merchantReference;
            return this;
        }

        /**
         * Sets the description.
         *
         * @param description a short text
         * @return this builder
         */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * Sets the callback URL.
         *
         * @param callbackUrl the URL, or {@code null}
         * @return this builder
         */
        public Builder callbackUrl(URI callbackUrl) {
            this.callbackUrl = callbackUrl;
            return this;
        }

        /**
         * Builds the request.
         *
         * @return the request
         * @throws IllegalArgumentException if a field is missing or invalid
         * @throws NullPointerException if a required object is missing
         */
        public PayoutRequest build() {
            return new PayoutRequest(idempotencyKey, amount, currency, phoneNumber, beneficiaryName,
                    merchantReference, description, callbackUrl);
        }
    }
}
