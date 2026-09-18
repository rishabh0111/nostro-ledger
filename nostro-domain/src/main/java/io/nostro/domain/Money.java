package io.nostro.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * An Amount: an exact quantity of one Currency, carried in that Currency's smallest unit.
 *
 * <p>Amounts in different Currencies never sum together; every operation refuses a mixed-currency
 * operand. Overflow is an {@link ArithmeticException}, never a wrap.
 *
 * @param minor    the quantity in the Currency's smallest unit (cents for USD, yen for JPY)
 * @param currency the Currency the quantity is denominated in
 */
public record Money(long minor, Currency currency) {

    public Money {
        Objects.requireNonNull(currency, "currency");
    }

    public static Money ofMinor(long minor, Currency currency) {
        return new Money(minor, currency);
    }

    /**
     * Builds an Amount from a decimal, refusing any value that cannot be represented exactly at the
     * Currency's scale. {@code 10.505 USD} is not a rounding question; it is not an Amount.
     */
    public static Money of(BigDecimal amount, Currency currency) {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        BigDecimal scaled;
        try {
            scaled = amount.setScale(currency.scale());
        } catch (ArithmeticException tooPrecise) {
            throw new IllegalArgumentException(
                    amount + " cannot be expressed at the scale of " + currency.code() + " (" + currency.scale() + ")",
                    tooPrecise);
        }
        return new Money(scaled.unscaledValue().longValueExact(), currency);
    }

    public BigDecimal toDecimal() {
        return BigDecimal.valueOf(minor, currency.scale());
    }

    public Money plus(Money other) {
        return new Money(Math.addExact(minor, sameCurrency(other).minor), currency);
    }

    public Money minus(Money other) {
        return new Money(Math.subtractExact(minor, sameCurrency(other).minor), currency);
    }

    public Money negate() {
        return new Money(Math.negateExact(minor), currency);
    }

    public boolean isZero() {
        return minor == 0;
    }

    public boolean isNegative() {
        return minor < 0;
    }

    private Money sameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!other.currency.equals(currency)) {
            throw new IllegalArgumentException(
                    "cannot combine " + currency.code() + " with " + other.currency.code());
        }
        return other;
    }

    @Override
    public String toString() {
        return toDecimal().toPlainString() + " " + currency.code();
    }
}
