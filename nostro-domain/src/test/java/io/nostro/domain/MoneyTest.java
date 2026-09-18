package io.nostro.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

    static final Currency USD = Currency.of("USD");
    static final Currency EUR = Currency.of("EUR");
    static final Currency JPY = Currency.of("JPY");

    @Test
    void carriesMinorUnitsOfOneCurrency() {
        var m = Money.ofMinor(1_050, USD);
        assertThat(m.minor()).isEqualTo(1_050);
        assertThat(m.currency()).isEqualTo(USD);
        assertThat(m.toDecimal()).isEqualByComparingTo("10.50");
    }

    @Test
    void aDecimalIsAcceptedOnlyAtTheScaleOfItsCurrency() {
        assertThat(Money.of(new BigDecimal("10.50"), USD)).isEqualTo(Money.ofMinor(1_050, USD));
        assertThat(Money.of(new BigDecimal("10.5"), USD)).isEqualTo(Money.ofMinor(1_050, USD));
        assertThat(Money.of(new BigDecimal("1200"), JPY)).isEqualTo(Money.ofMinor(1_200, JPY));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> Money.of(new BigDecimal("10.505"), USD))
                .withMessageContaining("scale");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Money.of(new BigDecimal("1.5"), JPY))
                .withMessageContaining("scale");
    }

    @Test
    void arithmeticStaysWithinOneCurrency() {
        var ten = Money.ofMinor(1_000, USD);
        var three = Money.ofMinor(300, USD);

        assertThat(ten.plus(three)).isEqualTo(Money.ofMinor(1_300, USD));
        assertThat(ten.minus(three)).isEqualTo(Money.ofMinor(700, USD));
        assertThat(three.negate()).isEqualTo(Money.ofMinor(-300, USD));
    }

    @Test
    void mixedCurrencyOperandsAreRefused() {
        var usd = Money.ofMinor(100, USD);
        var eur = Money.ofMinor(100, EUR);

        assertThatIllegalArgumentException().isThrownBy(() -> usd.plus(eur)).withMessageContaining("EUR");
        assertThatIllegalArgumentException().isThrownBy(() -> usd.minus(eur));
    }

    @Test
    void additionOverflowIsAnErrorNotAWrap() {
        var nearMax = Money.ofMinor(Long.MAX_VALUE, USD);
        assertThatThrownBy(() -> nearMax.plus(Money.ofMinor(1, USD))).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Money.ofMinor(Long.MIN_VALUE, USD).negate()).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void signPredicates() {
        assertThat(Money.ofMinor(0, USD).isZero()).isTrue();
        assertThat(Money.ofMinor(-1, USD).isNegative()).isTrue();
        assertThat(Money.ofMinor(1, USD).isNegative()).isFalse();
    }
}
