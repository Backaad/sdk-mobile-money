package io.github.backaad.mobilemoney.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConstantTimeTest {

    @Test
    void comparesBytes() {
        assertThat(ConstantTime.equals(new byte[] {1, 2, 3}, new byte[] {1, 2, 3})).isTrue();
        assertThat(ConstantTime.equals(new byte[] {1, 2, 3}, new byte[] {1, 2, 4})).isFalse();
        assertThat(ConstantTime.equals(new byte[] {1, 2, 3}, new byte[] {1, 2})).isFalse();
        assertThat(ConstantTime.equals((byte[]) null, new byte[] {1})).isFalse();
        assertThat(ConstantTime.equals((byte[]) null, null)).isFalse();
    }

    @Test
    void comparesStrings() {
        assertThat(ConstantTime.equals("abc", "abc")).isTrue();
        assertThat(ConstantTime.equals("abc", "abd")).isFalse();
        assertThat(ConstantTime.equals("abc", null)).isFalse();
    }
}
