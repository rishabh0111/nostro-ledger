package io.nostro.api.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nostro.api.LedgerIntegrationTest;
import io.nostro.api.problem.ProblemType;
import io.nostro.api.auth.ApiKey;
import io.nostro.api.auth.Permission;
import io.nostro.domain.AccountId;
import io.nostro.domain.Currency;
import io.nostro.domain.Money;
import io.nostro.domain.Posting;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/** An Account's history over HTTP: its Postings, newest first, paged by keyset. */
class AccountHistoryIT extends LedgerIntegrationTest {

    static final Currency USD = Currency.of("USD");

    @Test
    @DisplayName("an Account's history is its Postings, newest first, each with the Entry and Position it belongs to")
    void historyIsThePostingsNewestFirst() throws Exception {
        var tenant = newTenant();
        var key = newApiKey(tenant, Permission.LEDGER_READ, Permission.LEDGER_WRITE);
        var cash = new AccountId(openAccount(key, "cash"));
        var bank = new AccountId(openAccount(key, "bank"));
        var first = recordEntry(tenant, cash, bank, 1_000);
        var second = recordEntry(tenant, bank, cash, 250);
        var third = recordEntry(tenant, cash, bank, 5);

        var body = history(key, bank);

        assertThat(body.get("account").asString()).isEqualTo(bank.toString());
        assertThat(body.propertyNames()).containsExactly("account", "postings", "nextCursor");
        assertThat(body.get("nextCursor").isNull()).isTrue();
        var postings = body.get("postings");
        assertThat(postings).hasSize(3);
        assertThat(postings.valueStream().map(p -> p.get("entry").asString()))
                .containsExactly(third.entry().toString(), second.entry().toString(), first.entry().toString());
        assertThat(postings.valueStream().map(p -> p.get("amount").get("amount").asString()))
                .containsExactly("0.05", "-2.50", "10.00");
        assertThat(postings.valueStream().map(p -> p.get("amount").get("currency").asString())).containsOnly("USD");
        assertThat(postings.valueStream().map(p -> p.get("position").asString()))
                .containsExactly(third.position().token(), second.position().token(), first.position().token());
        assertThat(postings.get(0).propertyNames()).containsExactly("id", "entry", "amount", "position");
        assertThat(postings.valueStream().map(p -> UUID.fromString(p.get("id").asString()))).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("pages follow the cursor through the whole history with no gap and no repeat, even where one Entry posts twice to the Account")
    void pagesFollowTheCursor() throws Exception {
        var tenant = newTenant();
        var key = newApiKey(tenant, Permission.LEDGER_READ, Permission.LEDGER_WRITE);
        var cash = new AccountId(openAccount(key, "cash"));
        var bank = new AccountId(openAccount(key, "bank"));
        for (int i = 1; i <= 3; i++) {
            recordEntry(tenant, cash, bank, i);
        }
        // Two Postings against bank in one Entry share a Position: the tie the id breaks.
        recordEntry(tenant, List.of(new Posting(cash, Money.ofMinor(-9, USD)),
                new Posting(bank, Money.ofMinor(4, USD)), new Posting(bank, Money.ofMinor(5, USD))));
        recordEntry(tenant, cash, bank, 6);

        var everything = history(key, bank).get("postings");
        assertThat(everything).hasSize(6);

        var walked = new java.util.ArrayList<JsonNode>();
        String cursor = null;
        int pages = 0;
        do {
            var request = get("/v1/accounts/{id}/postings", bank.value()).queryParam("limit", "2");
            if (cursor != null) {
                request.queryParam("cursor", cursor);
            }
            var page = history(request, key);
            assertThat(page.get("postings")).hasSizeBetween(1, 2);
            page.get("postings").forEach(walked::add);
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asString();
            pages++;
            // A Posting recorded while paging is newer than every page and belongs to none of them.
            recordEntry(tenant, cash, bank, 100 + pages);
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(walked).containsExactlyElementsOf(everything);
        var amounts = walked.stream().map(p -> p.get("amount").get("amount").asString()).toList();
        assertThat(amounts.getFirst()).isEqualTo("0.06");
        assertThat(amounts.subList(1, 3)).containsExactlyInAnyOrder("0.05", "0.04"); // one Entry, one Position, order by id
        assertThat(amounts.subList(3, 6)).containsExactly("0.03", "0.02", "0.01");
        assertThat(history(key, bank).get("postings")).hasSize(9);
    }

    @Test
    @DisplayName("a cursor that is not ours, or a page size out of bounds, is 400 as Problem Details")
    void malformedPagingIsRefused() throws Exception {
        var tenant = newTenant();
        var key = newApiKey(tenant, Permission.LEDGER_READ, Permission.LEDGER_WRITE);
        var cash = new AccountId(openAccount(key, "cash"));
        var foreign = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("1:00000000000000000001/%s".formatted(uuid()).getBytes());

        for (var cursor : List.of("not-base64!", "bm90IGEgY3Vyc29y", foreign)) {
            http.perform(get("/v1/accounts/{id}/postings", cash.value()).queryParam("cursor", cursor)
                            .header(HttpHeaders.AUTHORIZATION, bearer(key)))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        }
        for (var limit : List.of("0", "201", "-1", "many")) {
            http.perform(get("/v1/accounts/{id}/postings", cash.value()).queryParam("limit", limit)
                            .header(HttpHeaders.AUTHORIZATION, bearer(key)))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        }
        assertThat(history(get("/v1/accounts/{id}/postings", cash.value()).queryParam("limit", "200"), key)
                .get("postings")).isEmpty();
    }

    @Test
    @DisplayName("another Tenant's Account has no history to read: 404 with an empty body")
    void anotherTenantsAccountIsAbsent() throws Exception {
        var a = newTenant();
        var b = newTenant();
        var keyOfA = newApiKey(a, Permission.LEDGER_READ, Permission.LEDGER_WRITE);
        var keyOfB = newApiKey(b, Permission.LEDGER_READ);
        var cashOfA = new AccountId(openAccount(keyOfA, "cash"));
        recordEntry(a, cashOfA, new AccountId(openAccount(keyOfA, "bank")), 100);

        http.perform(get("/v1/accounts/{id}/postings", cashOfA.value()).header(HttpHeaders.AUTHORIZATION, bearer(keyOfB)))
                .andExpect(problem(ProblemType.UNKNOWN_ACCOUNT));
        assertThat(history(keyOfA, cashOfA).get("postings")).hasSize(1);
    }

    // -- helpers ---------------------------------------------------------------------------------

    private JsonNode history(ApiKey key, AccountId account) throws Exception {
        return history(get("/v1/accounts/{id}/postings", account.value()), key);
    }

    private JsonNode history(MockHttpServletRequestBuilder request, ApiKey key) throws Exception {
        var result = http.perform(request.header(HttpHeaders.AUTHORIZATION, bearer(key)))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }


}
