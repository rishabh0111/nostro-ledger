package io.nostro.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * The unit an Account is denominated in, with a fixed smallest unit.
 *
 * <p>Carried as a value rather than a type parameter: a currency code arrives at runtime as a
 * string, and a {@code Money<USD>} cannot survive that boundary. The scale is what a {@link Money}
 * needs to convert to and from a decimal; every other operation is on minor units.
 *
 * @param code  ISO 4217 alphabetic code, upper case
 * @param scale number of decimal digits in the smallest unit (2 for USD, 0 for JPY, 3 for BHD)
 */
public record Currency(String code, int scale) {

    public Currency {
        if (code == null || !code.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency code must be three upper-case letters, got " + code);
        }
        if (scale < 0) {
            throw new IllegalArgumentException("currency scale must be non-negative, got " + scale);
        }
    }

    /** The strict path: an unknown code is a programmer error. Callers validate input with {@link #lookup}. */
    public static Currency of(String code) {
        return lookup(code).orElseThrow(() -> new IllegalArgumentException("unknown currency code: " + code));
    }

    /** The lenient path for caller-supplied input. Case-insensitive; empty for anything not ISO 4217. */
    public static Optional<Currency> lookup(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        try {
            var iso = java.util.Currency.getInstance(code.toUpperCase(Locale.ROOT));
            int digits = iso.getDefaultFractionDigits();
            if (digits < 0) {
                // Pseudo-currencies (XAU, XXX) have no smallest unit and cannot be posted.
                return Optional.empty();
            }
            return Optional.of(new Currency(iso.getCurrencyCode(), digits));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }
}
