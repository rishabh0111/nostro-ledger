package io.nostro.domain;

import java.util.Optional;

/**
 * Reads the Balance of one of the current Tenant's Accounts (ADR-0007).
 *
 * <p>Two implementations have served this contract: one sums Postings, the other asks a
 * projection. Either way the answer carries the Position it reflects, and a caller may present a
 * minimum Position it needs the answer to include. An implementation that can lag waits for that
 * minimum, bounded by the server; one that cannot lag has nothing to wait for. Neither refuses:
 * the caller reads the Position that came back and decides for itself.
 *
 * <p>The Tenant is ambient, from the credential (ADR-0006). An Account this Tenant does not have
 * is absent, not forbidden: the answer is empty, indistinguishable from an Account that was never
 * opened anywhere.
 */
public interface BalanceReader {

    Optional<Balance> read(AccountId account, Optional<Position> minimum);
}
