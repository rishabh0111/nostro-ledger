package io.nostro.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The system as a client meets it: the compose stack, over HTTP, nothing else (ADR-0012). Every
 * other test stops at its own deployable's boundary; this is the only one that crosses all of them —
 * the API service writing the outbox, the relay draining it into Kafka, the projection applying it
 * and answering over gRPC with the Tenant in the call's metadata. Proto drift, metadata that does
 * not cross, a compose file that does not wire: this is what would catch them.
 *
 * <p>System properties: {@code baseUrl} (http://localhost:8080) and {@code controlKey} (the compose
 * default). Each run makes Tenants of its own through the control plane, so it can run against a
 * stack that has already been used.
 */
class EndToEndIT {

    static final String BASE_URL = System.getProperty("baseUrl", "http://localhost:8080");
    static final String CONTROL_KEY = System.getProperty("controlKey", "nc_dev-only-control-key-change-before-any-deploy");

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static String alpha;
    private static String beta;

    @BeforeAll
    static void twoTenants() {
        alpha = tenantKey("e2e-alpha");
        beta = tenantKey("e2e-beta");
    }

    @Test
    @DisplayName("an Entry recorded over HTTP reaches the projected Balance through the relay, Kafka and gRPC, and the Balance is the sum of the ledger's Postings")
    void anEntryReachesTheProjectedBalance() {
        var cash = openAccount(alpha, "cash", true);
        var bank = openAccount(alpha, "bank", false);

        var first = record(alpha, bank, cash, "100.00");
        // Polled without a minimum: the Balance lags the write, and says by how much, until it does not.
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).until(() ->
                reflects(balance(alpha, cash, null).get("position").asString(), first));
        var caughtUp = balance(alpha, cash, null);
        assertThat(caughtUp.get("balance").get("amount").asString()).isEqualTo("100.00");
        assertThat(sumOfPostings(alpha, cash)).isEqualByComparingTo("100.00");

        // Read-your-writes: a read at the Position a write returned, sent the moment the write answers.
        var second = record(alpha, cash, bank, "40.00");
        var atItsPosition = balance(alpha, cash, second);
        assertThat(reflects(atItsPosition.get("position").asString(), second)).isTrue();
        assertThat(atItsPosition.get("balance").get("amount").asString()).isEqualTo("60.00");
        assertThat(sumOfPostings(alpha, cash)).isEqualByComparingTo("60.00");
        assertThat(balance(alpha, bank, second).get("balance").get("amount").asString()).isEqualTo("-60.00");
    }

    @Test
    @DisplayName("across every service boundary, a Tenant can neither see another's money nor reference it, and its Balances reflect only its own history")
    void aTenantCannotSeeOrReferenceAnothersMoney() {
        var cashOfAlpha = openAccount(alpha, "vault", true);
        var written = record(alpha, openAccount(alpha, "source", false), cashOfAlpha, "250.00");
        await().atMost(Duration.ofSeconds(30)).until(() -> reflects(balance(alpha, cashOfAlpha, null).get("position").asString(), written));

        // Beta, holding a valid credential of its own, asks about Alpha's Account: absent, not forbidden.
        assertThat(get(beta, "/v1/accounts/" + cashOfAlpha + "/balance").statusCode()).isEqualTo(404);
        assertThat(get(beta, "/v1/accounts/" + cashOfAlpha).statusCode()).isEqualTo(404);
        assertThat(get(beta, "/v1/accounts/" + cashOfAlpha + "/postings").statusCode()).isEqualTo(404);

        // A balanced Entry naming Alpha's Account is refused as an Account Beta does not have.
        var cashOfBeta = openAccount(beta, "cash", false);
        var steal = post(beta, "/v1/entries", entry(UUID.randomUUID().toString(), cashOfBeta, cashOfAlpha, "1.00"));
        assertThat(steal.statusCode()).isEqualTo(404);
        assertThat(JSON.readTree(steal.body()).get("type").asString()).endsWith("/unknown-account");

        // Beta's Balances reflect none of Alpha's history: the projection's watermark is per Tenant.
        var betasView = balance(beta, cashOfBeta, null);
        assertThat(betasView.get("balance").get("amount").asString()).isEqualTo("0.00");
        assertThat(betasView.get("position").asString()).endsWith(":00000000000000000000");
        assertThat(balance(alpha, cashOfAlpha, written).get("balance").get("amount").asString()).isEqualTo("250.00");
    }

    // -- the client ----------------------------------------------------------------------------------

    private static String tenantKey(String name) {
        var control = "Bearer " + CONTROL_KEY;
        var tenant = json(post(control, "/v1/control/tenants", "{\"name\": \"" + name + "-" + UUID.randomUUID() + "\"}"), 201).get("id").asString();
        return "Bearer " + json(post(control, "/v1/control/tenants/" + tenant + "/api-keys",
                "{\"label\": \"e2e\", \"permissions\": [\"LEDGER_READ\", \"LEDGER_WRITE\"]}"), 201).get("key").asString();
    }

    private static String openAccount(String key, String code, boolean constrained) {
        return json(post(key, "/v1/accounts", "{\"code\": \"%s\", \"currency\": \"USD\", \"constrained\": %s}"
                .formatted(code + "-" + UUID.randomUUID(), constrained)), 201).get("id").asString();
    }

    /** Moves the amount from one Account to the other and returns the Position the Entry created. */
    private static String record(String key, String from, String to, String amount) {
        return json(post(key, "/v1/entries", entry(UUID.randomUUID().toString(), from, to, amount)), 201).get("position").asString();
    }

    private static String entry(String idempotencyKey, String from, String to, String amount) {
        return """
                {"idempotencyKey": "%s", "postings": [
                  {"account": "%s", "amount": {"amount": "-%s", "currency": "USD"}},
                  {"account": "%s", "amount": {"amount": "%s", "currency": "USD"}}]}
                """.formatted(idempotencyKey, from, amount, to, amount);
    }

    private static JsonNode balance(String key, String account, String minPosition) {
        var path = "/v1/accounts/" + account + "/balance" + (minPosition == null ? "" : "?minPosition=" + minPosition);
        return json(get(key, path), 200);
    }

    /** The Account's net, from the ledger's own record of its Postings rather than from the projection. */
    private static BigDecimal sumOfPostings(String key, String account) {
        var sum = BigDecimal.ZERO;
        String cursor = null;
        do {
            var page = json(get(key, "/v1/accounts/" + account + "/postings" + (cursor == null ? "" : "?cursor=" + cursor)), 200);
            for (JsonNode posting : page.get("postings")) {
                sum = sum.add(new BigDecimal(posting.get("amount").get("amount").asString()));
            }
            cursor = page.hasNonNull("nextCursor") ? page.get("nextCursor").asString() : null;
        } while (cursor != null);
        return sum;
    }

    /** Whether a Position token reflects at least another: same installation, and an xid8 at or above it. */
    private static boolean reflects(String position, String minimum) {
        var have = position.split(":");
        var need = minimum.split(":");
        return have[0].equals(need[0]) && Long.compareUnsigned(Long.parseUnsignedLong(have[1]), Long.parseUnsignedLong(need[1])) >= 0;
    }

    private static JsonNode json(HttpResponse<String> response, int expected) {
        assertThat(response.statusCode()).as(response.uri() + " answered " + response.body()).isEqualTo(expected);
        return JSON.readTree(response.body());
    }

    private static HttpResponse<String> get(String key, String path) {
        return send(HttpRequest.newBuilder(URI.create(BASE_URL + path)).header("Authorization", key).GET().build());
    }

    private static HttpResponse<String> post(String key, String path, String body) {
        return send(HttpRequest.newBuilder(URI.create(BASE_URL + path))
                .header("Authorization", key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
    }

    private static HttpResponse<String> send(HttpRequest request) {
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(request.uri() + " did not answer; is the compose stack up?", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
