package io.nostro.load;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One arm's world: a Tenant of its own, made through the control plane as any operator would, an API
 * key, an unconstrained counterparty, and the Constrained Accounts the arm moves money through, each
 * funded before the run.
 */
record Arm(String name, String authorization, List<String> constrained, String counterparty) {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    static Arm setUp(String baseUrl, String controlKey, String name, int constrainedAccounts, long fundingMinor) {
        var suffix = UUID.randomUUID().toString().substring(0, 8);
        var control = "Bearer " + controlKey;
        var tenant = field(post(baseUrl + "/v1/control/tenants", control,
                "{\"name\": \"load-%s-%s\"}".formatted(name, suffix)), "id");
        var key = "Bearer " + field(post(baseUrl + "/v1/control/tenants/" + tenant + "/api-keys", control,
                "{\"label\": \"load test\", \"permissions\": [\"LEDGER_READ\", \"LEDGER_WRITE\"]}"), "key");

        var counterparty = account(baseUrl, key, "counterparty", false);
        var accounts = new ArrayList<String>();
        for (int i = 0; i < constrainedAccounts; i++) {
            var account = account(baseUrl, key, "constrained-" + i, true);
            post(baseUrl + "/v1/entries", key, entryJson("fund-" + account, account, fundingMinor, counterparty));
            accounts.add(account);
        }
        return new Arm(name, key, List.copyOf(accounts), counterparty);
    }

    /** An Entry moving {@code signedMinor} USD cents into the Constrained Account (out of it, if negative), and the opposite to the counterparty. */
    static String entryJson(String idempotencyKey, String constrained, long signedMinor, String counterparty) {
        return """
                {"idempotencyKey": "%s", "postings": [
                  {"account": "%s", "amount": {"amount": "%s", "currency": "USD"}},
                  {"account": "%s", "amount": {"amount": "%s", "currency": "USD"}}]}
                """.formatted(idempotencyKey, constrained, dollars(signedMinor), counterparty, dollars(-signedMinor));
    }

    private static String account(String baseUrl, String key, String code, boolean constrained) {
        return field(post(baseUrl + "/v1/accounts", key,
                "{\"code\": \"%s\", \"currency\": \"USD\", \"constrained\": %s}".formatted(code, constrained)), "id");
    }

    private static String dollars(long minor) {
        return BigDecimal.valueOf(minor, 2).toPlainString();
    }

    private static String post(String url, String authorization, String json) {
        try {
            var response = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                            .header("Authorization", authorization)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(json))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("setting up the load test: " + url + " answered " + response.statusCode() + " " + response.body());
            }
            return response.body();
        } catch (IOException e) {
            throw new IllegalStateException("setting up the load test: " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String field(String json, String name) {
        var matcher = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        if (!matcher.find()) {
            throw new IllegalStateException("no " + name + " in " + json);
        }
        return matcher.group(1);
    }
}
