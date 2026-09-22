package io.nostro.api.ledger;

import io.nostro.domain.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * An Amount on the wire: a decimal string at the Currency's scale, never a float. {@code "15.00"}
 * is fifteen dollars exactly; a JSON number would invite a client to parse it as one it is not.
 * The one representation of Money in every request and response; {@link Requests#money} reads it
 * back, and either field may be missing on the way in.
 */
@Schema(description = "An Amount: a decimal string at the Currency's scale, never a JSON number. \"15.00\" is fifteen dollars exactly.")
record MoneyJson(
        @Schema(example = "15.00", requiredMode = Schema.RequiredMode.REQUIRED) @Nullable String amount,
        @Schema(example = "USD", requiredMode = Schema.RequiredMode.REQUIRED) @Nullable String currency) {

    static MoneyJson of(Money money) {
        return new MoneyJson(money.toDecimal().toPlainString(), money.currency().code());
    }
}
