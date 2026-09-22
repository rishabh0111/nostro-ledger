package io.nostro.api.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nostro.api.LedgerIntegrationTest;
import io.nostro.api.auth.ApiKey;
import io.nostro.api.auth.Permission;
import io.nostro.api.problem.ProblemType;
import io.nostro.domain.Position;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Recording an Entry over HTTP, and every answer the ledger can give to it, as Problem Details
 * with a stable {@code type} (ADR-0014). The HTTP seam (ADR-0012): what a client sees, and
 * nothing about how the refusal was decided.
 */
class EntriesIT extends LedgerIntegrationTest {

    @Test
    @DisplayName("a balanced Entry is recorded: 201 with the Entry and the Position it created, and the Balance reflects it")
    void aBalancedEntryIsRecorded() throws Exception {
        var key = newApiKey(newTenant(), Permission.LEDGER_READ, Permission.LEDGER_WRITE);
        var cash = openAccount(key, "cash");
        var bank = openAccount(key, "bank");

        var result = record(key, entry(uuid(), posting(cash, "-15.00", "USD"), posting(bank, "15.00", "USD")))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        var body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.propertyNames()).containsExactly("entry", "position");
        var entry = UUID.fromString(body.get("entry").asString());
        var position = Position.parse(body.get("position").asString()).orElseThrow();

        var balance = json.readTree(http.perform(get("/v1/accounts/{id}/balance", bank).header(HttpHeaders.AUTHORIZATION, bearer(key)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(balance.get("balance").get("amount").asString()).isEqualTo("15.00");
        assertThat(Position.parse(balance.get("position").asString()).orElseThrow().isAtLeast(position)).isTrue();

        var history = json.readTree(http.perform(get("/v1/accounts/{id}/postings", bank).header(HttpHeaders.AUTHORIZATION, bearer(key)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(history.get("postings")).hasSize(1);
        assertThat(history.get("postings").get(0).get("entry").asString()).isEqualTo(entry.toString());
    }

    @Test
    @DisplayName("an Entry that does not balance within a Currency is 422 unbalanced, naming what nets to what")
    void anUnbalancedEntryIsRefused() throws Exception {
        var key = newApiKey(newTenant(), Permission.LEDGER_READ, Permission.LEDGER_WRITE);
        var cash = openAccount(key, "cash");
        var bank = openAccount(key, "bank");

        record(key, entry(uuid(), posting(cash, "-15.00", "USD"), posting(bank, "14.50", "USD")))
                .andExpect(problem(ProblemType.UNBALANCED))
                .andExpect(jsonPath("$.detail").value(containsString("USD nets to -0.50")));
    }

    @Test
    @DisplayName("a Posting against another Tenant's Account is 404 unknown-account, the same answer as for no Account at all")
    void anotherTenantsAccountIsAbsent() throws Exception {
        var keyOfA = newApiKey(newTenant(), Permission.LEDGER_READ, Permission.LEDGER_WRITE);
        var keyOfB = newApiKey(newTenant(), Permission.LEDGER_READ, Permission.LEDGER_WRITE);
        var cashOfA = openAccount(keyOfA, "cash");
        var bankOfB = openAccount(keyOfB, "bank");

        var foreign = json.readTree(record(keyOfA, entry(uuid(), posting(cashOfA, "-1.00", "USD"), posting(bankOfB, "1.00", "USD")))
                .andExpect(problem(ProblemType.UNKNOWN_ACCOUNT))
                .andReturn().getResponse().getContentAsString());
        var absent = json.readTree(record(keyOfA, entry(uuid(), posting(cashOfA, "-1.00", "USD"), posting(uuid(), "1.00", "USD")))
                .andExpect(problem(ProblemType.UNKNOWN_ACCOUNT))
                .andReturn().getResponse().getContentAsString());

        assertThat(foreign.propertyNames()).containsExactlyInAnyOrderElementsOf(absent.propertyNames());
        assertThat(foreign.get("detail").asString().replace(bankOfB.toString(), "X"))
                .isEqualTo(absent.get("detail").asString().replaceAll("[0-9a-f-]{36}", "X"));
        http.perform(get("/v1/accounts/{id}/balance", bankOfB).header(HttpHeaders.AUTHORIZATION, bearer(keyOfB)))
                .andExpect(jsonPath("$.balance.amount").value("0.00"));
    }

    @Test
    @DisplayName("a Posting in a Currency other than the Account's is 422 currency-mismatch")
    void aCurrencyMismatchIsRefused() throws Exception {
        var key = newApiKey(newTenant(), Permission.LEDGER_READ, Permission.LEDGER_WRITE);
        var cash = openAccount(key, "cash");
        var bank = openAccount(key, "bank");

        record(key, entry(uuid(), posting(cash, "-15.00", "EUR"), posting(bank, "15.00", "EUR")))
                .andExpect(problem(ProblemType.CURRENCY_MISMATCH))
                .andExpect(jsonPath("$.detail").value(containsString("USD")));
    }

    @Test
    @DisplayName("an Entry that would take a Constrained Account below zero is 422 insufficient-balance, and nothing is recorded")
    void aFloorBreachIsRefused() throws Exception {
        var key = newApiKey(newTenant(), Permission.LEDGER_READ, Permission.LEDGER_WRITE);
        var wallet = openAccount(key, "wallet", "USD", true);
        var bank = openAccount(key, "bank");
        record(key, entry(uuid(), posting(bank, "-10.00", "USD"), posting(wallet, "10.00", "USD"))).andExpect(status().isCreated());

        record(key, entry(uuid(), posting(wallet, "-10.01", "USD"), posting(bank, "10.01", "USD")))
                .andExpect(problem(ProblemType.INSUFFICIENT_BALANCE))
                .andExpect(jsonPath("$.detail").value(containsString(wallet.toString())));

        http.perform(get("/v1/accounts/{id}/balance", wallet).header(HttpHeaders.AUTHORIZATION, bearer(key)))
                .andExpect(jsonPath("$.balance.amount").value("10.00"));
    }

    @Test
    @DisplayName("the same Idempotency Key with the same request replays the first answer; with a different request it is 422")
    void anIdempotencyKeyIsHonoured() throws Exception {
        var key = newApiKey(newTenant(), Permission.LEDGER_READ, Permission.LEDGER_WRITE);
        var cash = openAccount(key, "cash");
        var bank = openAccount(key, "bank");
        var idempotencyKey = uuid();

        var first = record(key, entry(idempotencyKey, posting(cash, "-5.00", "USD"), posting(bank, "5.00", "USD")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var again = record(key, entry(idempotencyKey, posting(cash, "-5.00", "USD"), posting(bank, "5.00", "USD")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        assertThat(again).isEqualTo(first);
        record(key, entry(idempotencyKey, posting(cash, "-6.00", "USD"), posting(bank, "6.00", "USD")))
                .andExpect(problem(ProblemType.IDEMPOTENCY_KEY_REUSED))
                .andExpect(jsonPath("$.detail").value(containsString(idempotencyKey.toString())));
        http.perform(get("/v1/accounts/{id}/balance", bank).header(HttpHeaders.AUTHORIZATION, bearer(key)))
                .andExpect(jsonPath("$.balance.amount").value("5.00"));
    }

    @Test
    @DisplayName("a request that cannot be read as an Entry is 400 malformed, whether the framework or the domain says so")
    void aMalformedRequestIsRefused() throws Exception {
        var key = newApiKey(newTenant(), Permission.LEDGER_READ, Permission.LEDGER_WRITE);
        var cash = openAccount(key, "cash");
        var bank = openAccount(key, "bank");

        var offScale = entry(uuid(), posting(cash, "-10.005", "USD"), posting(bank, "10.005", "USD"));
        var zero = entry(uuid(), posting(cash, "0.00", "USD"), posting(bank, "0.00", "USD"));
        var noPostings = """
                {"idempotencyKey": "%s", "postings": []}
                """.formatted(uuid());
        var postingsMissing = """
                {"idempotencyKey": "%s"}
                """.formatted(uuid());
        var keyMissing = """
                {"postings": [%s, %s]}
                """.formatted(posting(cash, "-1.00", "USD"), posting(bank, "1.00", "USD"));
        var keyTooLong = entry("k".repeat(129), posting(cash, "-1.00", "USD"), posting(bank, "1.00", "USD"));
        var accountMissing = """
                {"idempotencyKey": "%s", "postings": [{"amount": {"amount": "1.00", "currency": "USD"}}]}
                """.formatted(uuid());
        var amountNotDecimal = """
                {"idempotencyKey": "%s", "postings": [{"account": "%s", "amount": {"amount": "ten", "currency": "USD"}}]}
                """.formatted(uuid(), cash);
        var accountNotUuid = """
                {"idempotencyKey": "%s", "postings": [{"account": "not-a-uuid", "amount": {"amount": "1.00", "currency": "USD"}}]}
                """.formatted(uuid());
        var notJson = "{not json";

        for (var body : List.of(offScale, zero, noPostings, postingsMissing, keyMissing, keyTooLong, accountMissing,
                amountNotDecimal, accountNotUuid, notJson)) {
            record(key, body).andExpect(problem(ProblemType.MALFORMED));
        }
    }

    @Test
    @DisplayName("a Posting in a currency the ledger cannot post is 400 unknown-currency")
    void anUnknownCurrencyIsRefused() throws Exception {
        var key = newApiKey(newTenant(), Permission.LEDGER_READ, Permission.LEDGER_WRITE);
        var cash = openAccount(key, "cash");
        var bank = openAccount(key, "bank");

        record(key, entry(uuid(), posting(cash, "-1.00", "XYZ"), posting(bank, "1.00", "XYZ")))
                .andExpect(problem(ProblemType.UNKNOWN_CURRENCY));
        record(key, entry(uuid(), posting(cash, "-1", "XAU"), posting(bank, "1", "XAU")))
                .andExpect(problem(ProblemType.UNKNOWN_CURRENCY));
    }

    @Test
    @DisplayName("a Reversing Entry undoes the one it names, once: its key replays it, another key is 422 already-reversed")
    void anEntryIsReversedAtMostOnce() throws Exception {
        var key = newApiKey(newTenant(), Permission.LEDGER_READ, Permission.LEDGER_WRITE);
        var cash = openAccount(key, "cash");
        var bank = openAccount(key, "bank");
        var recorded = json.readTree(record(key, entry(uuid(), posting(cash, "-15.00", "USD"), posting(bank, "15.00", "USD")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        var entry = recorded.get("entry").asString();

        var reversalKey = uuid();
        var reversal = json.readTree(reverse(key, entry, reversalKey)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(reversal.propertyNames()).containsExactly("entry", "position");
        assertThat(reversal.get("entry").asString()).isNotEqualTo(entry);
        http.perform(get("/v1/accounts/{id}/balance", bank).header(HttpHeaders.AUTHORIZATION, bearer(key)))
                .andExpect(jsonPath("$.balance.amount").value("0.00"));

        var again = json.readTree(reverse(key, entry, reversalKey)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(again).isEqualTo(reversal);
        reverse(key, entry, uuid())
                .andExpect(problem(ProblemType.ALREADY_REVERSED))
                .andExpect(jsonPath("$.detail").value(containsString(entry)));
    }

    @Test
    @DisplayName("reversing an Entry this Tenant does not have is 404 unknown-entry, whoever has it")
    void anUnknownEntryCannotBeReversed() throws Exception {
        var keyOfA = newApiKey(newTenant(), Permission.LEDGER_READ, Permission.LEDGER_WRITE);
        var keyOfB = newApiKey(newTenant(), Permission.LEDGER_READ, Permission.LEDGER_WRITE);
        var cashOfB = openAccount(keyOfB, "cash");
        var bankOfB = openAccount(keyOfB, "bank");
        var entryOfB = json.readTree(record(keyOfB, entry(uuid(), posting(cashOfB, "-1.00", "USD"), posting(bankOfB, "1.00", "USD")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("entry").asString();

        reverse(keyOfA, entryOfB, uuid()).andExpect(problem(ProblemType.UNKNOWN_ENTRY));
        reverse(keyOfA, uuid().toString(), uuid()).andExpect(problem(ProblemType.UNKNOWN_ENTRY));
        http.perform(get("/v1/accounts/{id}/balance", bankOfB).header(HttpHeaders.AUTHORIZATION, bearer(keyOfB)))
                .andExpect(jsonPath("$.balance.amount").value("1.00"));
    }

    @Test
    @DisplayName("a Reversing Entry is subject to the floor: a correction can be refused")
    void aReversalIsRefusedAtTheFloor() throws Exception {
        var key = newApiKey(newTenant(), Permission.LEDGER_READ, Permission.LEDGER_WRITE);
        var wallet = openAccount(key, "wallet", "USD", true);
        var bank = openAccount(key, "bank");
        var funding = json.readTree(record(key, entry(uuid(), posting(bank, "-10.00", "USD"), posting(wallet, "10.00", "USD")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("entry").asString();
        record(key, entry(uuid(), posting(wallet, "-10.00", "USD"), posting(bank, "10.00", "USD"))).andExpect(status().isCreated());

        reverse(key, funding, uuid()).andExpect(problem(ProblemType.INSUFFICIENT_BALANCE));
    }

    @Test
    @DisplayName("opening an Account with a code the Tenant already uses is 409 account-code-taken, not a 500")
    void aTakenAccountCodeIsAConflict() throws Exception {
        var key = newApiKey(newTenant(), Permission.LEDGER_READ, Permission.LEDGER_WRITE);
        openAccount(key, "cash");

        open(key, """
                {"code": "cash", "currency": "EUR", "constrained": true}
                """)
                .andExpect(problem(ProblemType.ACCOUNT_CODE_TAKEN))
                .andExpect(jsonPath("$.detail").value(containsString("cash")));
    }

    @Test
    @DisplayName("opening an Account in an unknown currency is 400 unknown-currency; with a blank code, 400 malformed")
    void anAccountThatCannotBeOpenedIsRefused() throws Exception {
        var key = newApiKey(newTenant(), Permission.LEDGER_READ, Permission.LEDGER_WRITE);

        open(key, """
                {"code": "gold", "currency": "XAU", "constrained": false}
                """).andExpect(problem(ProblemType.UNKNOWN_CURRENCY));
        open(key, """
                {"code": "   ", "currency": "USD", "constrained": false}
                """).andExpect(problem(ProblemType.MALFORMED));
        open(key, """
                {"currency": "USD", "constrained": false}
                """).andExpect(problem(ProblemType.MALFORMED));
    }

    @Test
    @DisplayName("a refusal the framework makes before any handler runs carries the malformed type, not about:blank")
    void frameworkRefusalsCarryTheMalformedType() throws Exception {
        var key = newApiKey(newTenant(), Permission.LEDGER_READ, Permission.LEDGER_WRITE);

        http.perform(get("/v1/accounts/{id}", "not-a-uuid").header(HttpHeaders.AUTHORIZATION, bearer(key)))
                .andExpect(problem(ProblemType.MALFORMED));
        http.perform(post("/v1/accounts").header(HttpHeaders.AUTHORIZATION, bearer(key))
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(problem(ProblemType.MALFORMED));
    }

    // -- helpers ---------------------------------------------------------------------------------

    private ResultActions record(ApiKey key, String body) throws Exception {
        return http.perform(json(post("/v1/entries"), key, body));
    }

    private ResultActions reverse(ApiKey key, String entry, UUID idempotencyKey) throws Exception {
        return http.perform(json(post("/v1/entries/{id}/reversal", entry), key, """
                {"idempotencyKey": "%s", "description": "undo"}
                """.formatted(idempotencyKey)));
    }

    private ResultActions open(ApiKey key, String body) throws Exception {
        return http.perform(json(post("/v1/accounts"), key, body));
    }

    private UUID openAccount(ApiKey key, String code, String currency, boolean constrained) throws Exception {
        var result = open(key, """
                {"code": "%s", "currency": "%s", "constrained": %s}
                """.formatted(code, currency, constrained)).andExpect(status().isCreated()).andReturn();
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("id").asString());
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, ApiKey key, String body) {
        return request.header(HttpHeaders.AUTHORIZATION, bearer(key)).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String entry(Object idempotencyKey, String... postings) {
        return """
                {"idempotencyKey": "%s", "description": "test", "postings": [%s]}
                """.formatted(idempotencyKey, String.join(", ", postings));
    }

    private static String posting(Object account, String amount, String currency) {
        return """
                {"account": "%s", "amount": {"amount": "%s", "currency": "%s"}}""".formatted(account, amount, currency);
    }
}
