package io.nostro.api.projection;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.nostro.balance.v1.BalanceServiceGrpc;
import io.nostro.balance.v1.GetBalanceRequest;
import io.nostro.balance.v1.GetBalanceResponse;
import io.nostro.domain.Account;
import io.nostro.domain.AccountId;
import io.nostro.domain.Balance;
import io.nostro.domain.BalanceReader;
import io.nostro.domain.Money;
import io.nostro.domain.Position;
import io.nostro.persistence.Installation;
import io.nostro.persistence.ledger.Accounts;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The Balance, as the projection holds it (ADR-0007). The first version summed Postings behind this same contract;
 * only what stands behind {@link BalanceReader} has moved.
 *
 * <p>Whether the Account exists is the ledger's to say, so it is asked first, of the ledger's own
 * database, in a transaction that has ended — and returned its connection — before the projection
 * is called. The projection is then asked with the minimum Position, and it does the waiting: this
 * side holds a request thread (a virtual one) and no connection while it does. That ordering is the
 * whole of "release the JDBC connection before parking a waiter".
 *
 * <p>The Position that comes back is the projection's watermark for the Tenant; one the projection
 * does not have yet is the zero Position, "none of this Tenant's history", exactly as the summing reader reported a
 * Tenant with no Entries. A watermark from another installation is not stale but wrong, and fails
 * loudly.
 */
@Component
class ProjectedBalanceReader implements BalanceReader {

    private static final Logger log = LoggerFactory.getLogger(ProjectedBalanceReader.class);

    /** The statuses that mean the projection could not be reached or could not answer in time. */
    private static final Set<Status.Code> UNAVAILABLE = Set.of(
            Status.Code.UNAVAILABLE, Status.Code.DEADLINE_EXCEEDED, Status.Code.RESOURCE_EXHAUSTED);

    private final Accounts accounts;
    private final BalanceServiceGrpc.BalanceServiceBlockingStub projection;
    private final Installation installation;
    private final BalanceProperties properties;

    ProjectedBalanceReader(Accounts accounts, BalanceServiceGrpc.BalanceServiceBlockingStub projection,
                           Installation installation, BalanceProperties properties) {
        this.accounts = accounts;
        this.projection = projection;
        this.installation = installation;
        this.properties = properties;
    }

    @Override
    public Optional<Balance> read(AccountId id, Optional<Position> minimum) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("a Balance read waits on the projection and must not hold a transaction's connection while it does");
        }
        Optional<Account> account = accounts.find(id);
        if (account.isEmpty()) {
            return Optional.empty();
        }
        var request = GetBalanceRequest.newBuilder().setAccountId(id.toString());
        minimum.ifPresent(position -> request.setMinPosition(position.token()));
        var answer = ask(request.build());

        var currency = account.get().currency();
        if (!answer.getCurrency().isEmpty() && !answer.getCurrency().equals(currency.code())) {
            throw new IllegalStateException("the projection holds Account " + id + " in " + answer.getCurrency()
                    + " and the ledger in " + currency.code());
        }
        Position reflected = answer.getPosition().isEmpty()
                ? installation.position(0)
                : installation.parse(answer.getPosition()).orElseThrow(() -> new IllegalStateException(
                        "the projection reports Position " + answer.getPosition() + ", which is not one of this ledger's"));
        return Optional.of(new Balance(id, Money.ofMinor(answer.getAmountMinor(), currency), reflected));
    }

    /** Asks, with a deadline, retrying only {@code UNAVAILABLE}: the one status that promises nothing was done. */
    private GetBalanceResponse ask(GetBalanceRequest request) {
        var backoff = properties.retryBackoff();
        for (int attempt = 1; ; attempt++) {
            try {
                return projection.withDeadlineAfter(properties.deadline().toMillis(), TimeUnit.MILLISECONDS).getBalance(request);
            } catch (StatusRuntimeException failed) {
                var code = failed.getStatus().getCode();
                if (code == Status.Code.UNAVAILABLE && attempt < properties.attempts()) {
                    log.warn("the projection was {} for a Balance; attempt {} of {}", code, attempt, properties.attempts());
                    pause(backoff);
                    backoff = backoff.multipliedBy(2);
                    continue;
                }
                if (UNAVAILABLE.contains(code)) {
                    log.warn("the projection did not answer for a Balance after {} attempt(s): {}", attempt, code);
                    throw new ProjectionUnavailable("the Balance could not be read: the projection did not answer (" + code + ")", failed);
                }
                throw failed;
            }
        }
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted retrying the projection", interrupted);
        }
    }
}
