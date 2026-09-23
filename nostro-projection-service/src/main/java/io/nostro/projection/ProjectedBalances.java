package io.nostro.projection;

import io.nostro.domain.AccountId;
import io.nostro.domain.Position;
import io.nostro.domain.TenantId;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Reads what the projection holds for a Tenant: an Account's Balance, and the watermark it
 * reflects, read in one statement so both come from one snapshot. At READ COMMITTED each statement
 * takes its own; a watermark read a statement later could name an Entry the Balance did not
 * include.
 */
@Component
public class ProjectedBalances {

    /**
     * What the projection holds for one Account.
     *
     * @param amountMinor the Balance in the Account's smallest unit; zero if no Posting has reached it
     * @param currency    the Account's Currency as the projection learned it, or empty if no Posting has
     * @param watermark   the largest Position of the Tenant's applied, or empty if none has been
     */
    public record Projected(long amountMinor, Optional<String> currency, Optional<Position> watermark) {
    }

    static final String BALANCE_AND_WATERMARK = """
            SELECT b.amount_minor, b.currency, w.ledger_installation, w.position::text AS position_token
              FROM (SELECT 1) AS one
              LEFT JOIN projected_balance b ON b.tenant_id = :tenant AND b.account_id = :account
              LEFT JOIN tenant_watermark w ON w.tenant_id = :tenant
            """;

    private final TenantTransactions transactions;

    ProjectedBalances(TenantTransactions transactions) {
        this.transactions = transactions;
    }

    /**
     * The Account's Balance as projected, acting for the Tenant. An Account of another Tenant is
     * indistinguishable from one with no Postings: row-level security shows this Tenant nothing else.
     */
    public Projected read(TenantId tenant, AccountId account) {
        return transactions.readingAs(tenant, jdbc -> jdbc.sql(BALANCE_AND_WATERMARK)
                .param("tenant", tenant.value())
                .param("account", account.value())
                .query((row, n) -> {
                    String position = row.getString("position_token");
                    return new Projected(
                            row.getLong("amount_minor"),
                            Optional.ofNullable(row.getString("currency")),
                            position == null
                                    ? Optional.empty()
                                    : Optional.of(new Position(row.getLong("ledger_installation"), Long.parseUnsignedLong(position))));
                })
                .single());
    }
}
