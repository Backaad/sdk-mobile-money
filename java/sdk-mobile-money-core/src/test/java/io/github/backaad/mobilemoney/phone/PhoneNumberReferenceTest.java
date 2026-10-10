package io.github.backaad.mobilemoney.phone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.backaad.mobilemoney.InvalidPhoneNumberException;
import io.github.backaad.mobilemoney.testing.TestData;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/** Runs the reference numbers of testdata/phone-numbers.json, which the Dart tests will read too. */
class PhoneNumberReferenceTest {

    private static final PrefixTable TABLE = PrefixTable.cameroonDefault();

    static Stream<Arguments> valid() {
        return cases("valid").map(c -> Arguments.of(Named.of(c.get("input").asString(), c)));
    }

    static Stream<Arguments> invalid() {
        return cases("invalid").map(c -> Arguments.of(
                Named.of(c.get("input").asString() + " (" + c.get("note").asString() + ")", c)));
    }

    private static Stream<JsonNode> cases(String kind) {
        JsonNode list = TestData.read("phone-numbers.json").get(kind);
        return list.valueStream();
    }

    @ParameterizedTest
    @MethodSource("valid")
    void normalizesValidNumbers(JsonNode c) {
        PhoneNumber number = PhoneNumber.parse(c.get("input").asString());

        assertThat(number.e164()).isEqualTo(c.get("e164").asString());
        assertThat(number.nationalNumber()).isEqualTo(c.get("national").asString());
        assertThat(number.countryCode()).isEqualTo("237");
        JsonNode operator = c.get("operator");
        if (operator.isNull()) {
            assertThat(TABLE.operatorOf(number)).isEmpty();
        } else {
            assertThat(TABLE.operatorOf(number)).contains(MobileOperator.valueOf(operator.asString()));
        }
    }

    @ParameterizedTest
    @MethodSource("invalid")
    void rejectsInvalidNumbersWithTheExpectedReason(JsonNode c) {
        assertThatThrownBy(() -> PhoneNumber.parse(c.get("input").asString()))
                .isInstanceOfSatisfying(InvalidPhoneNumberException.class, e -> assertThat(e.reason())
                        .isEqualTo(InvalidPhoneNumberException.Reason.valueOf(c.get("reason").asString())));
    }

    @Test
    void defaultPrefixTableIsTheSharedOne() {
        Map<String, MobileOperator> expected = new TreeMap<>();
        TestData.read("phone-numbers.json").get("defaultPrefixTable").properties().forEach(entry -> {
            MobileOperator operator = MobileOperator.valueOf(entry.getKey());
            entry.getValue().valueStream().forEach(range -> {
                String[] bounds = range.asString().split("-");
                for (int p = Integer.parseInt(bounds[0]); p <= Integer.parseInt(bounds[1]); p++) {
                    expected.put(Integer.toString(p), operator);
                }
            });
        });

        assertThat(TABLE.prefixes()).isEqualTo(expected);
    }
}
