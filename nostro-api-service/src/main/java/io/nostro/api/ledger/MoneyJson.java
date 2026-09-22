package io.nostro.api.ledger;

import io.nostro.domain.Money;

/**
 * An Amount on the wire: a decimal string at the Currency's scale, never a float. {@code "15.00"}
 * is fifteen dollars exactly; a JSON number would invite a client to parse it as one it is not.
 * The one representation of Money in every request and response.
 */
record MoneyJson(String amount, String currency) {

    static MoneyJson of(Money money) {
        return new MoneyJson(money.toDecimal().toPlainString(), money.currency().code());
    }
}
