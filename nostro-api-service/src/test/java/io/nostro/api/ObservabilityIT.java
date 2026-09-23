package io.nostro.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nostro.api.auth.Permission;
import io.nostro.api.reconciliation.Reconciliation;
import io.nostro.domain.AccountId;
import io.nostro.domain.Currency;
import io.nostro.domain.IdempotencyKey;
import io.nostro.domain.Money;
import io.nostro.domain.Posting;
import io.nostro.domain.RecordEntry;
import io.nostro.domain.RecordOutcome.InsufficientBalance;
import io.nostro.persistence.tenant.TenantContext;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * What the running API service says about itself: health for compose, metrics for a scraper, a
 * trace that continues across the gRPC boundary, and a reconciliation that asserts ADR-0004's
 * invariant continuously rather than only in CI.
 */
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityIT extends LedgerIntegrationTest {

    @Autowired
    Reconciliation reconciliation;

    @Test
    @DisplayName("health, liveness and readiness answer without a credential")
    void healthNeedsNoCredential() throws Exception {
        for (var path : List.of("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness")) {
            http.perform(get(path)).andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("the write path is measured: latency split by whether a Constrained Account's row was locked, and floor refusals counted")
    void theWritePathIsMeasured() throws Exception {
        var tenant = newTenant();
        var key = newApiKey(tenant, Permission.LEDGER_READ, Permission.LEDGER_WRITE);
        var wallet = new AccountId(openConstrainedAccount(key, "wallet"));
        var bank = new AccountId(openAccount(key, "bank"));
        var other = new AccountId(openAccount(key, "other"));
        recordEntry(tenant, bank, wallet, 500);
        recordEntry(tenant, bank, other, 500);
        var usd = Currency.of("USD");
        var overdraw = TenantContext.runAs(tenant, () -> recorder.record(new RecordEntry(new IdempotencyKey(UUID.randomUUID().toString()),
                List.of(new Posting(wallet, Money.ofMinor(-501, usd)), new Posting(bank, Money.ofMinor(501, usd))), null)));
        assertThat(overdraw).isInstanceOf(InsufficientBalance.class);

        var metrics = prometheus();

        assertThat(metrics).containsPattern(sample("nostro_ledger_entries_write_seconds_count", "constrained=\"true\"", "outcome=\"recorded\""));
        assertThat(metrics).containsPattern(sample("nostro_ledger_entries_write_seconds_count", "constrained=\"false\"", "outcome=\"recorded\""));
        assertThat(metrics).containsPattern(sample("nostro_ledger_entries_write_seconds_count", "constrained=\"true\"", "outcome=\"refused\""));
        assertThat(metrics).containsPattern(Pattern.compile("^nostro_ledger_floor_rejections_total(\\{[^}]*})? [1-9]", Pattern.MULTILINE));
    }

    @Test
    @DisplayName("a Balance read continues the caller's trace into the gRPC call: one trace from HTTP to the projection")
    void oneTraceCrossesTheGrpcBoundary() throws Exception {
        var tenant = newTenant();
        var key = newApiKey(tenant, Permission.LEDGER_READ, Permission.LEDGER_WRITE);
        var cash = openAccount(key, "cash");
        var random = new Random();
        var traceId = HexFormat.of().formatHex(bytes(random, 16));
        int before = projection().traceparents().size();

        http.perform(get("/v1/accounts/{id}/balance", cash)
                        .header(HttpHeaders.AUTHORIZATION, bearer(key))
                        .header("traceparent", "00-" + traceId + "-" + HexFormat.of().formatHex(bytes(random, 8)) + "-01"))
                .andExpect(status().isOk());

        var sent = projection().traceparents().subList(before, projection().traceparents().size());
        assertThat(sent).singleElement().satisfies(traceparent ->
                assertThat(traceparent).as("the traceparent the projection received").startsWith("00-" + traceId + "-"));
    }

    @Test
    @DisplayName("a stored balance that disagrees with its Postings is a metric, not only a log line, and agreement brings it back to zero")
    void reconciliationFailureIsAMetric() throws Exception {
        var tenant = newTenant();
        var key = newApiKey(tenant, Permission.LEDGER_READ, Permission.LEDGER_WRITE);
        var wallet = new AccountId(openConstrainedAccount(key, "wallet"));
        recordEntry(tenant, new AccountId(openAccount(key, "bank")), wallet, 1_000);
        assertThat(reconciliation.run()).doesNotContain(wallet);

        // What only a bug or a hand in the database could do: move the stored balance and nothing else.
        asOwner().sql("UPDATE account SET balance_minor = balance_minor + 1 WHERE tenant_id = ? AND id = ?")
                .params(tenant.value(), wallet.value()).update();
        try {
            assertThat(reconciliation.run()).contains(wallet);
            assertThat(prometheus()).containsPattern(Pattern.compile("^nostro_reconciliation_disagreements(\\{[^}]*})? [1-9]", Pattern.MULTILINE));
        } finally {
            asOwner().sql("UPDATE account SET balance_minor = balance_minor - 1 WHERE tenant_id = ? AND id = ?")
                    .params(tenant.value(), wallet.value()).update();
        }

        assertThat(reconciliation.run()).isEmpty();
        assertThat(prometheus()).containsPattern(Pattern.compile("^nostro_reconciliation_disagreements(\\{[^}]*})? 0\\.0", Pattern.MULTILINE));
    }

    @Test
    @DisplayName("a request's log lines are JSON carrying its Tenant and its trace, so one request can be followed through the logs")
    void logLinesCarryTheTenantAndTheTrace(CapturedOutput output) throws Exception {
        var tenant = newTenant();
        var key = newApiKey(tenant, Permission.LEDGER_READ, Permission.LEDGER_WRITE);
        var cash = openAccount(key, "cash");
        var traceId = HexFormat.of().formatHex(bytes(new Random(), 16));
        projection().makeUnreachable(tenant.value());
        try {
            // The one request-path code that logs: retrying, then giving up on, the projection.
            http.perform(get("/v1/accounts/{id}/balance", cash)
                            .header(HttpHeaders.AUTHORIZATION, bearer(key))
                            .header("traceparent", "00-" + traceId + "-" + HexFormat.of().formatHex(bytes(new Random(), 8)) + "-01"))
                    .andExpect(status().isServiceUnavailable());
        } finally {
            projection().recover(tenant.value());
        }

        var lines = output.getOut().lines().filter(line -> line.contains("the projection did not answer")).toList();
        assertThat(lines).isNotEmpty().allSatisfy(line -> {
            var logged = json.readTree(line);
            assertThat(logged.get("tenant").asString()).isEqualTo(tenant.toString());
            assertThat(logged.get("traceId").asString()).isEqualTo(traceId);
            assertThat(logged.get("log").get("level").asString()).isEqualTo("WARN");
        });
    }

    // -- helpers ---------------------------------------------------------------------------------

    private String prometheus() throws Exception {
        return http.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** A Prometheus sample line of the metric carrying every label given, in any order. */
    private static Pattern sample(String metric, String... labels) {
        var pattern = new StringBuilder("^").append(Pattern.quote(metric)).append("\\{");
        for (String label : labels) {
            pattern.append("(?=[^}]*").append(Pattern.quote(label)).append(")");
        }
        return Pattern.compile(pattern.append("[^}]*} [1-9]").toString(), Pattern.MULTILINE);
    }

    private UUID openConstrainedAccount(io.nostro.api.auth.ApiKey key, String code) throws Exception {
        var result = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/accounts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(key))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "%s", "currency": "USD", "constrained": true}
                                """.formatted(code)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("id").asString());
    }

    private static byte[] bytes(Random random, int length) {
        var bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }
}
