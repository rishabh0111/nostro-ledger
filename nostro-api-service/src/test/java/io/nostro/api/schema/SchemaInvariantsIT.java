package io.nostro.api.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.nostro.api.LedgerIntegrationTest;
import io.nostro.domain.TenantId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The schema holds the invariants. Every test here goes around the application code on purpose,
 * using raw SQL as the request-path role, because the claim is that the database refuses these
 * writes no matter who issues them.
 */
class SchemaInvariantsIT extends LedgerIntegrationTest {

    @Test
    @DisplayName("the request-path role is neither a superuser nor exempt from row-level security")
    void theRequestPathRoleBypassesNothing() {
        var role = inTransactionWithoutTenant(() -> jdbc()
                .sql("SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user")
                .query().singleRow());

        assertThat(role).containsEntry("rolsuper", false).containsEntry("rolbypassrls", false);
    }

    @Test
    @DisplayName("a cross-tenant composite-foreign-key write is refused")
    void aCrossTenantReferenceIsRefusedByTheForeignKey() {
        var a = newTenant();
        var b = newTenant();
        var accountOfB = openAccount(b, "cash", "USD", false);
        var entryOfA = uuid();

        var failure = catchThrowable(() -> inTransactionAs(a, () -> {
            insertEntry(a, entryOfA);
            insertPosting(a, entryOfA, accountOfB, "USD", 100);
        }));

        assertThat(sqlState(failure)).isEqualTo("23503");
        assertThat(failure).hasMessageContaining("posting_applies_to_account_in_its_currency");
    }

    @Test
    @DisplayName("a cross-tenant read returns no rows rather than erroring")
    void aCrossTenantReadReturnsNothing() {
        var a = newTenant();
        var b = newTenant();
        var accountOfB = openAccount(b, "cash", "USD", false);

        List<Map<String, Object>> seenByA = inTransactionAs(a, () -> jdbc()
                .sql("SELECT id FROM account WHERE id = ?").param(accountOfB).query().listOfRows());
        List<Map<String, Object>> seenByB = inTransactionAs(b, () -> jdbc()
                .sql("SELECT id FROM account WHERE id = ?").param(accountOfB).query().listOfRows());

        assertThat(seenByA).isEmpty();
        assertThat(seenByB).hasSize(1);
    }

    @Test
    @DisplayName("with no Tenant bound, reads see no rows and writes are refused")
    void noTenantContextFailsClosed() {
        var a = newTenant();
        openAccount(a, "cash", "USD", false);

        var seen = inTransactionWithoutTenant(() -> jdbc().sql("SELECT id FROM account").query().listOfRows());
        var write = catchThrowable(() -> inTransactionWithoutTenant(() -> jdbc()
                .sql("INSERT INTO account (tenant_id, id, code, currency, constrained) VALUES (?, ?, ?, ?, ?)")
                .params(a.value(), uuid(), "orphan", "USD", false)
                .update()));

        assertThat(seen).isEmpty();
        assertThat(sqlState(write)).isEqualTo("42501");
    }

    @Test
    @DisplayName("entry and posting are insert-only for the request-path role")
    void entryAndPostingCannotBeUpdatedOrDeleted() {
        var a = newTenant();
        var cash = openAccount(a, "cash", "USD", false);
        var bank = openAccount(a, "bank", "USD", false);
        var entry = uuid();
        inTransactionAs(a, () -> {
            insertEntry(a, entry);
            insertPosting(a, entry, cash, "USD", -100);
            insertPosting(a, entry, bank, "USD", 100);
        });

        var updatePosting = catchThrowable(() -> inTransactionAs(a, () -> jdbc()
                .sql("UPDATE posting SET amount_minor = 1 WHERE entry_id = ?").param(entry).update()));
        var deleteEntry = catchThrowable(() -> inTransactionAs(a, () -> jdbc()
                .sql("DELETE FROM entry WHERE id = ?").param(entry).update()));
        var deletePosting = catchThrowable(() -> inTransactionAs(a, () -> jdbc()
                .sql("DELETE FROM posting WHERE entry_id = ?").param(entry).update()));

        assertThat(sqlState(updatePosting)).isEqualTo("42501");
        assertThat(sqlState(deleteEntry)).isEqualTo("42501");
        assertThat(sqlState(deletePosting)).isEqualTo("42501");
    }

    @Test
    @DisplayName("the request-path role may move an Account's balance and change nothing else about it")
    void accountDeclarationsAreImmutable() {
        var a = newTenant();
        var cash = openAccount(a, "cash", "USD", false);

        var moved = inTransactionAs(a, () -> jdbc()
                .sql("UPDATE account SET balance_minor = balance_minor + 5 WHERE id = ?").param(cash).update());
        var recurrency = catchThrowable(() -> inTransactionAs(a, () -> jdbc()
                .sql("UPDATE account SET currency = 'EUR' WHERE id = ?").param(cash).update()));
        var reconstrain = catchThrowable(() -> inTransactionAs(a, () -> jdbc()
                .sql("UPDATE account SET constrained = true WHERE id = ?").param(cash).update()));
        var byOwner = catchThrowable(() -> asOwner()
                .sql("UPDATE account SET currency = 'EUR' WHERE id = ?").param(cash).update());

        assertThat(moved).isEqualTo(1);
        assertThat(sqlState(recurrency)).isEqualTo("42501");
        assertThat(sqlState(reconstrain)).isEqualTo("42501");
        assertThat(failureOf(byOwner)).hasMessageContaining("account_declarations_are_immutable");
    }

    @Test
    @DisplayName("a Constrained Account cannot go negative, even by a raw UPDATE with no guard")
    void theFloorIsACheckConstraint() {
        var a = newTenant();
        var wallet = openAccount(a, "wallet", "USD", true);
        var overdraft = openAccount(a, "overdraft", "USD", false);

        var breach = catchThrowable(() -> inTransactionAs(a, () -> jdbc()
                .sql("UPDATE account SET balance_minor = -1 WHERE id = ?").param(wallet).update()));
        var allowed = inTransactionAs(a, () -> jdbc()
                .sql("UPDATE account SET balance_minor = -1 WHERE id = ?").param(overdraft).update());

        assertThat(sqlState(breach)).isEqualTo("23514");
        assertThat(breach).hasMessageContaining("account_constrained_non_negative");
        assertThat(allowed).isEqualTo(1);
    }

    @Test
    @DisplayName("an unbalanced Entry is refused at COMMIT, even by raw SQL")
    void anUnbalancedEntryIsRefusedByTheSchema() {
        var a = newTenant();
        var cash = openAccount(a, "cash", "USD", false);
        var bank = openAccount(a, "bank", "EUR", false);
        var lopsided = uuid();
        var crossed = uuid();

        var oneLeg = catchThrowable(() -> inTransactionAs(a, () -> {
            insertEntry(a, lopsided);
            insertPosting(a, lopsided, cash, "USD", 100);
        }));
        var perCurrency = catchThrowable(() -> inTransactionAs(a, () -> {
            insertEntry(a, crossed);
            insertPosting(a, crossed, cash, "USD", -100);
            insertPosting(a, crossed, bank, "EUR", 100);
        }));
        var recorded = inTransactionAs(a, () -> jdbc()
                .sql("SELECT count(*) FROM entry WHERE id IN (?, ?)").params(lopsided, crossed).query(Long.class).single());

        assertThat(sqlState(oneLeg)).isEqualTo("23514");
        assertThat(oneLeg).hasMessageContaining("entry_balances_per_currency");
        assertThat(sqlState(perCurrency)).isEqualTo("23514");
        assertThat(recorded).isZero();
    }

    @Test
    @DisplayName("a Posting must be in its Account's Currency")
    void aPostingCarriesItsAccountsCurrency() {
        var a = newTenant();
        var usd = openAccount(a, "usd", "USD", false);
        var eur = openAccount(a, "eur", "EUR", false);
        var entry = uuid();

        var failure = catchThrowable(() -> inTransactionAs(a, () -> {
            insertEntry(a, entry);
            insertPosting(a, entry, usd, "EUR", -100);
            insertPosting(a, entry, eur, "EUR", 100);
        }));

        assertThat(sqlState(failure)).isEqualTo("23503");
        assertThat(failure).hasMessageContaining("posting_applies_to_account_in_its_currency");
    }

    @Test
    @DisplayName("an Entry can be reversed at most once")
    void anEntryIsReversedAtMostOnce() {
        var a = newTenant();
        var cash = openAccount(a, "cash", "USD", false);
        var bank = openAccount(a, "bank", "USD", false);
        var original = uuid();
        inTransactionAs(a, () -> {
            insertEntry(a, original);
            insertPosting(a, original, cash, "USD", -100);
            insertPosting(a, original, bank, "USD", 100);
        });
        inTransactionAs(a, () -> {
            insertReversingEntry(a, uuid(), original);
        });

        var second = catchThrowable(() -> inTransactionAs(a, () -> insertReversingEntry(a, uuid(), original)));

        assertThat(sqlState(second)).isEqualTo("23505");
        assertThat(second).hasMessageContaining("entry_reversed_at_most_once");
    }

    // -- raw SQL fixtures, as the request-path role under the Tenant given ------------------------

    private UUID openAccount(TenantId tenant, String code, String currency, boolean constrained) {
        var id = uuid();
        inTransactionAs(tenant, () -> jdbc()
                .sql("INSERT INTO account (tenant_id, id, code, currency, constrained) VALUES (?, ?, ?, ?, ?)")
                .params(tenant.value(), id, code, currency, constrained)
                .update());
        return id;
    }

    private void insertEntry(TenantId tenant, UUID id) {
        jdbc().sql("INSERT INTO entry (tenant_id, id) VALUES (?, ?)").params(tenant.value(), id).update();
    }

    private void insertReversingEntry(TenantId tenant, UUID id, UUID reverses) {
        jdbc().sql("INSERT INTO entry (tenant_id, id, reverses_entry_id) VALUES (?, ?, ?)")
                .params(tenant.value(), id, reverses).update();
    }

    private void insertPosting(TenantId tenant, UUID entry, UUID account, String currency, long amountMinor) {
        jdbc().sql("INSERT INTO posting (tenant_id, id, entry_id, account_id, currency, amount_minor) VALUES (?, ?, ?, ?, ?, ?)")
                .params(tenant.value(), uuid(), entry, account, currency, amountMinor).update();
    }

    private static Throwable failureOf(Throwable t) {
        assertThat(t).as("expected a failure").isNotNull();
        return t;
    }
}
