package io.github.backaad.mobilemoney.phone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PrefixTableTest {

    @Test
    void defaultTableMatchesTheDocumentedRanges() {
        PrefixTable table = PrefixTable.cameroonDefault();

        assertThat(table.prefixes()).hasSize(5 + 10 + 4 + 5 + 10);
        assertThat(table.prefixes().get("650")).isEqualTo(MobileOperator.MTN);
        assertThat(table.prefixes().get("655")).isEqualTo(MobileOperator.ORANGE);
        assertThat(table.prefixes()).doesNotContainKeys("620", "640", "684", "689");
    }

    @Test
    void tableIsConfigurable() {
        PrefixTable table = PrefixTable.builder().range(MobileOperator.ORANGE, 640, 640).build();

        assertThat(table.operatorOf(PhoneNumber.parse("640123456"))).contains(MobileOperator.ORANGE);
        assertThat(table.operatorOf(PhoneNumber.parse("670123456"))).isEmpty();
    }

    @Test
    void rejectsConflictingRanges() {
        PrefixTable.Builder builder = PrefixTable.builder().range(MobileOperator.MTN, 650, 654);

        assertThatThrownBy(() -> builder.range(MobileOperator.ORANGE, 654, 656))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("654");
    }

    @Test
    void rejectsNonMobilePrefixes() {
        assertThatThrownBy(() -> PrefixTable.builder().range(MobileOperator.MTN, 599, 601))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PrefixTable.builder().range(MobileOperator.MTN, 660, 650))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void prefixesAreReadOnly() {
        assertThatThrownBy(() -> PrefixTable.cameroonDefault().prefixes().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
