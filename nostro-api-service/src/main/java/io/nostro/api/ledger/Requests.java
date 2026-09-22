package io.nostro.api.ledger;

import io.nostro.api.problem.ProblemType;
import io.nostro.domain.Currency;
import io.nostro.domain.Money;
import java.math.BigDecimal;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * From what a request said to what the domain takes, refusing on the way (ADR-0014). A field that
 * is missing, or that the domain's own constructors will not accept, is {@code 400 malformed}: the
 * request could not be read as what the endpoint takes. A currency code the ledger cannot post is
 * {@code 400 unknown-currency}, distinguished because a client can act on it.
 */
final class Requests {

    private Requests() {
    }

    static <T> T required(@Nullable T value, String field) {
        if (value == null) {
            throw ProblemType.MALFORMED.exception(field + " is required");
        }
        return value;
    }

    /** Builds a domain value, turning the constructor's refusal into the request's. */
    static <T> T domain(Supplier<T> construct) {
        try {
            return construct.get();
        } catch (IllegalArgumentException invalid) {
            throw ProblemType.MALFORMED.exception(invalid.getMessage());
        }
    }

    static Currency currency(String code) {
        return Currency.lookup(code).orElseThrow(() ->
                ProblemType.UNKNOWN_CURRENCY.exception("'" + code + "' is not a currency the ledger can post"));
    }

    /** An Amount from its wire form: a decimal string at the Currency's scale, exactly. */
    static Money money(@Nullable MoneyJson json, String field) {
        var wire = required(json, field);
        var currency = currency(required(wire.currency(), field + ".currency"));
        var text = required(wire.amount(), field + ".amount");
        BigDecimal decimal;
        try {
            decimal = new BigDecimal(text);
        } catch (NumberFormatException notDecimal) {
            throw ProblemType.MALFORMED.exception(field + ".amount is not a decimal: '" + text + "'");
        }
        return domain(() -> Money.of(decimal, currency));
    }
}
