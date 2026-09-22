package io.nostro.api.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nostro.api.LedgerIntegrationTest;
import io.nostro.api.auth.ApiKey;
import io.nostro.api.auth.Permission;
import io.nostro.domain.AccountId;
import io.nostro.domain.Position;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/**
 * The Balance read over HTTP (ADR-0007): for now a {@code SUM} over Postings behind the contract the projection
 * will serve. The response shape asserted here is the contract.
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

        var result = http.perform(get("/accounts/{id}/balance", bank.value())
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
            http.perform(get("/accounts/{id}/balance", cash.value())
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
            http.perform(get("/accounts/{id}/balance", id).header(HttpHeaders.AUTHORIZATION, bearer(keyOfB)))
                    .andExpect(status().isNotFound())
                    .andExpect(content().string(""));
        }
        assertThat(balance(keyOfA, cashOfA).get("balance").get("amount").asString()).isEqualTo("-1.00");
    }

    // -- helpers ---------------------------------------------------------------------------------

    private JsonNode balance(ApiKey key, AccountId account) throws Exception {
        var result = http.perform(get("/accounts/{id}/balance", account.value()).header(HttpHeaders.AUTHORIZATION, bearer(key)))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

}
