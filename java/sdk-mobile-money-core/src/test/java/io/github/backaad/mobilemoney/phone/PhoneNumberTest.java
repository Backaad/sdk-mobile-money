package io.github.backaad.mobilemoney.phone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.backaad.mobilemoney.InvalidPhoneNumberException;
import org.junit.jupiter.api.Test;

class PhoneNumberTest {

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> PhoneNumber.parse(null)).isInstanceOf(InvalidPhoneNumberException.class);
    }

    @Test
    void rejectsVeryLongInput() {
        assertThatThrownBy(() -> PhoneNumber.parse("6".repeat(1000)))
                .isInstanceOf(InvalidPhoneNumberException.class)
                .hasMessageStartingWith("Phone number is too long");
    }

    @Test
    void errorMessagesExplainTheExpectedFormat() {
        assertThatThrownBy(() -> PhoneNumber.parse("12345"))
                .hasMessageContaining("9 digits starting with 6");
    }

    @Test
    void valueSemantics() {
        PhoneNumber a = PhoneNumber.parse("+237 670 12 34 56");
        PhoneNumber b = PhoneNumber.parse("670123456");

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(PhoneNumber.parse("670123457"));
    }

    @Test
    void toStringMasksTheNumber() {
        PhoneNumber number = PhoneNumber.parse("670123456");

        assertThat(number.masked()).isEqualTo("+2376******56");
        assertThat(number).hasToString("+2376******56");
        assertThat(number.toString()).doesNotContain("0123");
    }
}
