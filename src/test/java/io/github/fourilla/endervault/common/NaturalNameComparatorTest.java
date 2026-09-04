package io.github.fourilla.endervault.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class NaturalNameComparatorTest {

    @Test
    void comparesNumericChunksByValueInsteadOfLexically() {
        List<String> names = new ArrayList<>(List.of(
                "Q11",
                "Q2-3",
                "Q1",
                "Q02-3",
                "Q2-10"
        ));

        names.sort(NaturalNameComparator.INSTANCE);

        assertThat(names).containsExactly("Q1", "Q2-3", "Q2-10", "Q02-3", "Q11");
    }

    @Test
    void usesShorterDigitRunsWhenNumericValuesAreEqual() {
        List<String> names = new ArrayList<>(List.of("page002", "page02", "page2"));

        names.sort(NaturalNameComparator.INSTANCE);

        assertThat(names).containsExactly("page2", "page02", "page002");
    }

    @Test
    void supportsNumbersLargerThanPrimitiveNumericTypes() {
        List<String> names = new ArrayList<>(List.of(
                "part100000000000000000000000000000",
                "part9",
                "part99999999999999999999999999999"
        ));

        names.sort(NaturalNameComparator.INSTANCE);

        assertThat(names).containsExactly(
                "part9",
                "part99999999999999999999999999999",
                "part100000000000000000000000000000"
        );
    }

    @Test
    void comparesTextCaseInsensitivelyAndKeepsAnExactTieBreaker() {
        List<String> names = new ArrayList<>(List.of("alpha10", "Alpha2", "alpha2"));

        names.sort(NaturalNameComparator.INSTANCE);

        assertThat(names).containsExactly("Alpha2", "alpha2", "alpha10");
    }

    @Test
    void handlesKoreanNamesWithNumericChunks() {
        List<String> names = new ArrayList<>(List.of("\ubb38\uc11c11", "\ubb38\uc11c3", "\ubb38\uc11c2"));

        names.sort(NaturalNameComparator.INSTANCE);

        assertThat(names).containsExactly("\ubb38\uc11c2", "\ubb38\uc11c3", "\ubb38\uc11c11");
    }
}
