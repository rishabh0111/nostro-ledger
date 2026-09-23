package io.nostro.api.reconciliation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.nostro.api.control.ControlPlane;
import io.nostro.domain.AccountId;
import io.nostro.domain.TenantId;
import io.nostro.persistence.ledger.StoredBalances;
import io.nostro.persistence.tenant.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Checks, on a schedule, that every Constrained Account's stored balance is the sum of its Postings
 * (ADR-0004), and says so as a metric: {@code nostro.reconciliation.disagreements} is the number of
 * Accounts that disagreed at the last run, and anything but zero is visible without reading a log.
 *
 * <p>The control plane says which Tenants exist; each one is then checked through the request path,
 * acting for that Tenant under its row-level security, like any request. Read-only, so every API
 * instance may run it.
 */
@Component
public class Reconciliation {

    private static final Logger log = LoggerFactory.getLogger(Reconciliation.class);

    private final ControlPlane controlPlane;
    private final StoredBalances storedBalances;
    private final AtomicLong disagreements = new AtomicLong();
    private final AtomicLong lastRun = new AtomicLong();
    private final Counter runs;

    Reconciliation(ControlPlane controlPlane, StoredBalances storedBalances, MeterRegistry meters) {
        this.controlPlane = controlPlane;
        this.storedBalances = storedBalances;
        Gauge.builder("nostro.reconciliation.disagreements", disagreements, AtomicLong::get)
                .description("Constrained Accounts whose stored balance was not the sum of their Postings at the last run; must be zero")
                .register(meters);
        Gauge.builder("nostro.reconciliation.last.run", lastRun, AtomicLong::get)
                .description("When reconciliation last completed, in epoch seconds")
                .baseUnit("seconds")
                .register(meters);
        this.runs = Counter.builder("nostro.reconciliation.runs").description("Completed reconciliation runs").register(meters);
    }

    /** One run over every Tenant; returns the Accounts that disagreed, which the gauge now counts. */
    @Scheduled(fixedDelayString = "${nostro.reconciliation.interval:60s}", initialDelayString = "${nostro.reconciliation.interval:60s}")
    public List<AccountId> run() {
        var found = new ArrayList<AccountId>();
        for (TenantId tenant : controlPlane.tenants()) {
            var disagreeing = TenantContext.runAs(tenant, storedBalances::disagreeingWithTheirPostings);
            if (!disagreeing.isEmpty()) {
                log.error("RECONCILIATION: Tenant {} has Constrained Accounts whose stored balance is not the sum of their Postings: {}",
                        tenant, disagreeing);
                found.addAll(disagreeing);
            }
        }
        disagreements.set(found.size());
        lastRun.set(Instant.now().getEpochSecond());
        runs.increment();
        return List.copyOf(found);
    }
}
