package io.nostro.load;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.during;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Two arms at the same concurrency, one after the other, each in a Tenant of its own.
 *
 * <p><b>Contended:</b> every request moves money into or out of one Constrained Account, so every
 * request takes that Account's row lock (ADR-0004). <b>Spread:</b> the same requests, spread across
 * {@code spreadAccounts} Constrained Accounts, so they rarely meet. The difference between the two is
 * what one hot row costs.
 *
 * <p>Each request is a debit or a credit of the same amount, at random, against an unconstrained
 * counterparty; each Constrained Account starts funded with five moves' worth. That is a random walk
 * that keeps reaching zero, so the floor refuses throughout the run rather than only at the end. The
 * claim under test: <b>every refusal in the contended arm is the floor, and nothing else</b> — no lock
 * timeout, no deadlock, no serialization failure surfacing as a 5xx. Anything else means the design is
 * wrong, whatever the throughput.
 *
 * <p>System properties: {@code baseUrl}, {@code controlKey}, {@code users} (32), {@code armSeconds}
 * (60), {@code spreadAccounts} (64).
 */
public class ContentionSimulation extends Simulation {

    static final String BASE_URL = System.getProperty("baseUrl", "http://localhost:8080");
    static final String CONTROL_KEY = System.getProperty("controlKey", "nc_dev-only-control-key-change-before-any-deploy");
    static final int USERS = Integer.getInteger("users", 32);
    static final Duration ARM = Duration.ofSeconds(Integer.getInteger("armSeconds", 60));
    static final int SPREAD_ACCOUNTS = Integer.getInteger("spreadAccounts", 64);

    static final long AMOUNT_MINOR = 100;
    static final long FUNDED_MOVES = 5;

    private final Arm contended = Arm.setUp(BASE_URL, CONTROL_KEY, "contended", 1, AMOUNT_MINOR * FUNDED_MOVES);
    private final Arm spread = Arm.setUp(BASE_URL, CONTROL_KEY, "spread", SPREAD_ACCOUNTS, AMOUNT_MINOR * FUNDED_MOVES);

    {
        // Every user starts at once and loops for the length of the arm: a closed model at constant concurrency.
        setUp(run(contended).injectOpen(atOnceUsers(USERS))
                .andThen(run(spread).injectOpen(atOnceUsers(USERS))))
                .protocols(http.baseUrl(BASE_URL).shareConnections());
    }

    @Override
    public void after() {
        Outcomes.report(USERS, ARM);
    }

    private static ScenarioBuilder run(Arm arm) {
        // exitASAP off: the loop finishes the iteration it is in, so the last response of each user is counted too.
        return scenario(arm.name()).exec(during(ARM, "iteration", false).on(recordAnEntry(arm)));
    }

    private static ChainBuilder recordAnEntry(Arm arm) {
        return exec(http(arm.name())
                .post("/v1/entries")
                .header("Authorization", arm.authorization())
                .header("Content-Type", "application/json")
                .body(StringBody(session -> entry(arm)))
                // Saved first, so every outcome is counted, including the ones the last check fails.
                .check(status().saveAs("status"))
                .check(jsonPath("$.type").optional().saveAs("type"))
                .check(status().in(201, 422)))
                .exec(session -> {
                    Outcomes.count(arm.name(), session.getInt("status"), session.contains("type") ? session.getString("type") : null);
                    return session.remove("type");
                });
    }

    /** A debit or a credit of the Constrained Account, at random, against the counterparty. */
    private static String entry(Arm arm) {
        var random = ThreadLocalRandom.current();
        List<String> accounts = arm.constrained();
        var constrained = accounts.get(random.nextInt(accounts.size()));
        long signed = random.nextBoolean() ? AMOUNT_MINOR : -AMOUNT_MINOR;
        return Arm.entryJson(UUID.randomUUID().toString(), constrained, signed, arm.counterparty());
    }
}
