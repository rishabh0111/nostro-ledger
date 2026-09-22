package io.nostro.api.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.zaxxer.hikari.HikariDataSource;
import io.nostro.api.LedgerIntegrationTest;
import io.nostro.domain.Account;
import io.nostro.domain.AccountId;
import io.nostro.domain.Currency;
import io.nostro.domain.EntryRecorder;
import io.nostro.domain.IdempotencyKey;
import io.nostro.domain.LedgerCommand;
import io.nostro.domain.Money;
import io.nostro.domain.Posting;
import io.nostro.domain.RecordEntry;
import io.nostro.domain.RecordOutcome;
import io.nostro.domain.RecordOutcome.Recorded;
import io.nostro.domain.ReverseEntry;
import io.nostro.domain.TenantId;
import io.nostro.persistence.entity.AccountEntity;
import io.nostro.persistence.entity.TenantScopedId;
import io.nostro.persistence.ledger.Accounts;
import io.nostro.persistence.tenant.TenantContext;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.hibernate.StatelessSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;

/**
 * The Tenant reaches the database through one hook at the transaction boundary, and no route can
 * forget it (docs/research/rls-pooling.md section 2). These tests go through the real
 * request-path components — {@link Accounts} and the {@link EntryRecorder} — rather than raw SQL,
 * which {@code SchemaInvariantsIT} already covers.
 */
class TenantContextIT extends LedgerIntegrationTest {

    static final Currency USD = Currency.of("USD");

    @Autowired
    Accounts accounts;

    @Autowired
    EntryRecorder recorder;

    @Autowired
    StatelessSession statelessSession;

    @Autowired
    Environment environment;

    @Test
    @DisplayName("the hook is attached to the one transaction manager, so every transaction passes through it")
    void theHookIsAttachedToTheTransactionManager() {
        var listeners = ((AbstractPlatformTransactionManager) transactionManager).getTransactionExecutionListeners();

        assertThat(listeners)
                .extracting(listener -> listener.getClass().getSimpleName())
                .contains("TenantContextTransactionListener");
    }

    @Test
    @DisplayName("Open Session In View is off: nothing runs after COMMIT on a connection with no Tenant")
    void openSessionInViewIsOff() {
        assertThat(environment.getProperty("spring.jpa.open-in-view", Boolean.class)).isFalse();
    }

    @Test
    @DisplayName("acting for one Tenant and naming another's Account, the request path sees no rows")
    void anotherTenantsAccountIsInvisible() {
        var a = newTenant();
        var b = newTenant();
        var accountOfB = open(b, "cash", false);

        var seenByA = TenantContext.runAs(a, () -> accounts.find(accountOfB));
        var seenByB = TenantContext.runAs(b, () -> accounts.find(accountOfB));

        assertThat(seenByA).isEmpty();
        assertThat(seenByB).isPresent();
    }

    @Test
    @DisplayName("with no Tenant bound, the request path refuses before the database, and the database would show nothing anyway")
    void noTenantBoundFailsClosedTwice() {
        var a = newTenant();
        var account = open(a, "cash", false);

        var refusal = catchThrowable(() -> accounts.find(account));
        var seenByTheDatabase = inTransactionWithoutTenant(() ->
                statelessSession.get(AccountEntity.class, TenantScopedId.of(a, account.value())));

        assertThat(refusal).isInstanceOf(IllegalStateException.class).hasMessageContaining("no Tenant is bound");
        assertThat(seenByTheDatabase).as("the same query the request path would run, with no Tenant set").isNull();
    }

    @Test
    @DisplayName("the Tenant is set before the first application query of a transaction, through every access path")
    void theTenantIsSetBeforeTheFirstQuery() {
        var tenant = newTenant();
        var account = open(tenant, "cash", false);

        // The first statement each transaction issues is the lookup itself; if the hook ran late,
        // that lookup would see no rows.
        var throughTheStatelessSession = inTransactionAs(tenant, () ->
                statelessSession.get(AccountEntity.class, TenantScopedId.of(tenant, account.value())));
        var throughJdbc = inTransactionAs(tenant, () -> jdbc()
                .sql("SELECT count(*) FROM account WHERE id = ?").param(account.value()).query(Long.class).single());

        assertThat(throughTheStatelessSession).isNotNull();
        assertThat(throughJdbc).isEqualTo(1);
    }

    @Test
    @DisplayName("with a pool of one connection, the whole request path still completes: nothing borrows a second one inside a transaction")
    void nothingInTheRequestPathOpensASecondConnectionInsideATransaction() throws SQLException {
        var tenant = newTenant();
        var pool = dataSource.unwrap(HikariDataSource.class);
        var config = pool.getHikariConfigMXBean();
        int maximumPoolSize = config.getMaximumPoolSize();

        // A second borrow while the transaction holds its connection could only be satisfied by a
        // second connection. With one in the pool it blocks until Hikari's connection timeout and
        // fails loudly, which is what turns "sets the Tenant on a connection nothing then queries"
        // from silent into visible. A borrow after COMMIT is not caught here; that is the Open
        // Session In View shape, asserted off above. (The timeout itself is not lowered: Hikari
        // applies that change only on its next housekeeping pass, so a failure here costs the
        // default 30 seconds.)
        config.setMaximumPoolSize(1);
        pool.getHikariPoolMXBean().softEvictConnections();
        try {
            var wallet = open(tenant, "wallet", true);
            var bank = open(tenant, "bank", false);
            var funded = record(tenant, new RecordEntry(key(), List.of(
                    new Posting(bank, Money.ofMinor(-500, USD)), new Posting(wallet, Money.ofMinor(500, USD))), "fund"));
            var spend = new RecordEntry(key(), List.of(
                    new Posting(wallet, Money.ofMinor(-200, USD)), new Posting(bank, Money.ofMinor(200, USD))), "spend");
            var spent = record(tenant, spend);
            var replayed = record(tenant, spend);
            var reversed = record(tenant, new ReverseEntry(key(), ((Recorded) spent).entry(), "undo"));
            var balance = TenantContext.runAs(tenant, () -> accounts.storedBalanceMinor(wallet));

            assertThat(funded).isInstanceOf(Recorded.class);
            assertThat(spent).isInstanceOf(Recorded.class);
            assertThat(replayed).isEqualTo(spent);
            assertThat(reversed).isInstanceOf(Recorded.class);
            assertThat(balance).contains(500L);
        } finally {
            config.setMaximumPoolSize(maximumPoolSize);
        }
    }

    private AccountId open(TenantId tenant, String code, boolean constrained) {
        var account = new Account(AccountId.random(), code, USD, constrained);
        return TenantContext.runAs(tenant, () -> accounts.open(account)).id();
    }

    private RecordOutcome record(TenantId tenant, LedgerCommand command) {
        return TenantContext.runAs(tenant, () -> recorder.record(command));
    }

    private static IdempotencyKey key() {
        return new IdempotencyKey(UUID.randomUUID().toString());
    }
}
