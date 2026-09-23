package io.nostro.api.reconciliation;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Runs {@link Reconciliation} every {@code nostro.reconciliation.interval}, one minute by default. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class ReconciliationSchedule {
}
