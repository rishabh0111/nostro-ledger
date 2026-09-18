package io.nostro.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class CurrencyTest {

    @Test
    void anIsoCodeCarriesItsSmallestUnit() {
        assertThat(Currency.of("USD").scale()).isEqualTo(2);
        assertThat(Currency.of("JPY").scale()).isEqualTo(0);
        assertThat(Currency.of("BHD").scale()).isEqualTo(3);
    }

    @Test
    void lookupIsOptionalForCallerInput() {
        assertThat(Currency.lookup("usd")).contains(Currency.of("USD"));
        assertThat(Currency.lookup("XXQ")).isEmpty();
        assertThat(Currency.lookup("")).isEmpty();
        assertThat(Currency.lookup(null)).isEmpty();
    }

    @Test
    void unknownCodesAreAProgrammerErrorOnTheStrictPath() {
        assertThatIllegalArgumentException().isThrownBy(() -> Currency.of("XXQ"));
    }

    @Test
    void codesAreNormalisedToUpperCase() {
        assertThat(Currency.of("usd")).isEqualTo(Currency.of("USD"));
        assertThat(Currency.of("usd").code()).isEqualTo("USD");
    }
}
