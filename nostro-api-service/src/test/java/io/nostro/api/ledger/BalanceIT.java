package io.nostro.api.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zaxxer.hikari.HikariDataSource;
import io.nostro.api.LedgerIntegrationTest;
import io.nostro.api.problem.ProblemType;
import io.nostro.api.auth.ApiKey;
import io.nostro.api.auth.Permission;
import io.nostro.domain.AccountId;
import io.nostro.domain.Position;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/**
 * The Balance read over HTTP (ADR-0007): the contract first served from a {@code SUM} over Postings and now
 * served from the projection. Here the projection is {@link io.nostro.api.StandInProjection}; the real one
 * meets this service in the end-to-end test.
 */
class BalanceIT extends LedgerIntegrationTest {

    @Test
    @DisplayName("a Balance is the net of every Posting against the Account, reported with the Position it reflects")
    void balanceIsTheNetOfPostings() throws Exception {
        var tenant = newTenant();
        var key = newApiKey(tenant, Permission.LEDGER_READ, Permission.LEDGER_WRITE);
        var cash = new AccountId(openAccount(key, "cash"));
        var bank = new AccountId(openAccount(key, "bank"));
        recordEntry(tenant, cash, bank, 1_250);
        var last = recordEntry(tenant, cash, bank, 250);

        var body = balance(key, bank);

        assertThat(body.get("account").asString()).isEqualTo(bank.toString());
        assertThat(body.get("balance").get("amount").asString()).isEqualTo("15.00");
        assertThat(body.get("balance").get("currency").asString()).isEqualTo("USD");
        assertThat(Position.parse(body.get("position").asString())).hasValueSatisfying(reflected ->
                assertThat(reflected.isAtLeast(last.position())).isTrue());
        assertThat(body.propertyNames()).containsExactly("account", "balance", "position");
    }

    @Test
    @DisplayName("a minimum Position is accepted, already satisfied, and the response is 200 with the Position reflected")
    void aMinimumPositionIsAlreadySatisfied() throws Exception {
        var tenant = newTenant();
        var key = newApiKey(tenant, Permission.LEDGER_READ, Permission.LEDGER_WRITE);
        var cash = new AccountId(openAccount(key, "cash"));
        var bank = new AccountId(openAccount(key, "bank"));
        var written = recordEntry(tenant, cash, bank, 700);

        var result = http.perform(get("/v1/accounts/{id}/balance", bank.value())
                        .queryParam("minPosition", written.position().token())
                        .header(HttpHeaders.AUTHORIZATION, bearer(key)))
                .andExpect(status().isOk())
                .andReturn();

        var body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("balance").get("amount").asString()).isEqualTo("7.00");
        assertThat(Position.parse(body.get("position").asString()).orElseThrow().isAtLeast(written.position())).isTrue();
    }

    @Test
    @DisplayName("a minimum Position that is not a Position of this installation is 400, not a comparison")
    void aForeignOrMalformedMinimumIsRefused() throws Exception {
        var tenant = newTenant();
        var key = newApiKey(tenant, Permission.LEDGER_READ, Permission.LEDGER_WRITE);
        var cash = new AccountId(openAccount(key, "cash"));
        var written = recordEntry(tenant, cash, new AccountId(openAccount(key, "bank")), 1);
        var otherInstallation = new Position(written.position().installation() + 1, written.position().xid8()).token();

        for (var minimum : List.of("not-a-position", "1:2", otherInstallation)) {
            http.perform(get("/v1/accounts/{id}/balance", cash.value())
                            .queryParam("minPosition", minimum)
                            .header(HttpHeaders.AUTHORIZATION, bearer(key)))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        }
    }

    @Test
    @DisplayName("an Account with no Postings, in a Tenant with no Entries, has a zero Balance and reflects none of its history")
    void aFreshAccountHasAZeroBalanceAtPositionZero() throws Exception {
        var tenant = newTenant();
        var key = newApiKey(tenant, Permission.LEDGER_READ, Permission.LEDGER_WRITE);
        var cash = new AccountId(openAccount(key, "cash"));

        var body = balance(key, cash);

        assertThat(body.get("balance").get("amount").asString()).isEqualTo("0.00");
        assertThat(body.get("balance").get("currency").asString()).isEqualTo("USD");
        var installation = asOwner().sql("SELECT system_identifier FROM installation").query(Long.class).single();
        assertThat(body.get("position").asString()).isEqualTo(new Position(installation, 0).token());
    }

    @Test
    @DisplayName("another Tenant's Account has no Balance to read: 404 with an empty body, exactly as an Account that does not exist")
    void anotherTenantsAccountIsAbsent() throws Exception {
        var a = newTenant();
        var b = newTenant();
        var keyOfA = newApiKey(a, Permission.LEDGER_READ, Permission.LEDGER_WRITE);
        var keyOfB = newApiKey(b, Permission.LEDGER_READ);
        var cashOfA = new AccountId(openAccount(keyOfA, "cash"));
        recordEntry(a, cashOfA, new AccountId(openAccount(keyOfA, "bank")), 100);

        for (var id : List.of(cashOfA.value(), uuid())) {
            http.perform(get("/v1/accounts/{id}/balance", id).header(HttpHeaders.AUTHORIZATION, bearer(keyOfB)))
                    .andExpect(problem(ProblemType.UNKNOWN_ACCOUNT));
        }
        assertThat(balance(keyOfA, cashOfA).get("balance").get("amount").asString()).isEqualTo("-1.00");
    }

    @Test
    @DisplayName("the Tenant reaches the projection in the call's metadata, from the credential, without the endpoint sending it")
    void theTenantTravelsToTheProjection() throws Exception {
        var tenant = newTenant();
        var key = newApiKey(tenant, Permission.LEDGER_READ, Permission.LEDGER_WRITE);
        var cash = new AccountId(openAccount(key, "cash"));
        int before = projection().callers().size();

        balance(key, cash);

        assertThat(projection().callers().subList(before, projection().callers().size())).containsExactly(tenant.value());
    }

    @Test
    @DisplayName("a lagging projection under load does not exhaust the pool: forty reads wait for a Position, holding no connection, while writes go on")
    void aLaggingProjectionDoesNotExhaustThePool() throws Exception {
        var tenant = newTenant();
        var key = newApiKey(tenant, Permission.LEDGER_READ, Permission.LEDGER_WRITE);
        var cash = new AccountId(openAccount(key, "cash"));
        var bank = new AccountId(openAccount(key, "bank"));
        var reflected = recordEntry(tenant, cash, bank, 100);
        projection().stall(tenant.value());
        try {
            var written = recordEntry(tenant, cash, bank, 23);
            var pool = dataSource.unwrap(HikariDataSource.class);
            assertThat(pool.getMaximumPoolSize()).isLessThan(40);

            try (var readers = Executors.newFixedThreadPool(40)) {
                var waiting = new ArrayList<Future<JsonNode>>();
                for (int i = 0; i < 40; i++) {
                    waiting.add(readers.submit(() -> balanceAtLeast(key, bank, written.position())));
                }
                Thread.sleep(300);

                assertThat(pool.getHikariPoolMXBean().getActiveConnections()).as("connections held by waiting reads").isZero();
                var started = Instant.now();
                recordEntry(tenant, cash, bank, 1);
                assertThat(Duration.between(started, Instant.now())).as("a write while forty reads wait").isLessThan(Duration.ofMillis(500));

                for (var read : waiting) {
                    // 200, with the Position the projection actually reflects: lag disclosed, not refused.
                    var body = read.get(10, TimeUnit.SECONDS);
                    assertThat(body.get("balance").get("amount").asString()).isEqualTo("1.00");
                    assertThat(body.get("position").asString()).isEqualTo(reflected.position().token());
                }
            }
        } finally {
            projection().recover(tenant.value());
        }
    }

    @Test
    @DisplayName("a projection that does not answer is a 503 with Retry-After, after a bounded number of attempts — never a stale or invented Balance")
    void anUnreachableProjectionIsA503() throws Exception {
        var tenant = newTenant();
        var key = newApiKey(tenant, Permission.LEDGER_READ, Permission.LEDGER_WRITE);
        var cash = new AccountId(openAccount(key, "cash"));
        projection().makeUnreachable(tenant.value());
        try {
            int before = projection().callers().size();

            http.perform(get("/v1/accounts/{id}/balance", cash.value()).header(HttpHeaders.AUTHORIZATION, bearer(key)))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

            assertThat(projection().callers().size() - before).as("attempts").isEqualTo(3);
        } finally {
            projection().recover(tenant.value());
        }
        assertThat(balance(key, cash).get("balance").get("amount").asString()).isEqualTo("0.00");
    }

    @Test
    @DisplayName("a projection too slow to answer in time is asked again: a Balance read has no effect to duplicate, so one slow answer is not a 503")
    void aSlowAnswerIsRetried() throws Exception {
        var tenant = newTenant();
        var key = newApiKey(tenant, Permission.LEDGER_READ, Permission.LEDGER_WRITE);
        var cash = new AccountId(openAccount(key, "cash"));
        var bank = new AccountId(openAccount(key, "bank"));
        recordEntry(tenant, bank, cash, 900);
        int before = projection().callers().size();
        projection().slowDown(tenant.value(), 1);
        try {
            assertThat(balance(key, cash).get("balance").get("amount").asString()).isEqualTo("9.00");
        } finally {
            projection().recover(tenant.value());
        }
        assertThat(projection().callers().size() - before).as("attempts").isEqualTo(2);
    }

    // -- helpers ---------------------------------------------------------------------------------

    private JsonNode balanceAtLeast(ApiKey key, AccountId account, Position minimum) throws Exception {
        var result = http.perform(get("/v1/accounts/{id}/balance", account.value())
                        .queryParam("minPosition", minimum.token())
                        .header(HttpHeaders.AUTHORIZATION, bearer(key)))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode balance(ApiKey key, AccountId account) throws Exception {
        var result = http.perform(get("/v1/accounts/{id}/balance", account.value()).header(HttpHeaders.AUTHORIZATION, bearer(key)))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

}
