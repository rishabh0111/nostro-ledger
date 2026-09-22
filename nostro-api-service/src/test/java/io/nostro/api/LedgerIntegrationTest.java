package io.nostro.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nostro.api.auth.ApiKey;
import io.nostro.api.auth.Permission;
import io.nostro.domain.AccountId;
import io.nostro.domain.Currency;
import io.nostro.domain.EntryRecorder;
import io.nostro.domain.IdempotencyKey;
import io.nostro.domain.Money;
import io.nostro.domain.Posting;
import io.nostro.domain.RecordEntry;
import io.nostro.domain.RecordOutcome.Recorded;
import io.nostro.domain.TenantId;
import io.nostro.persistence.tenant.TenantContext;
import io.nostro.testsupport.LedgerPostgres;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one Spring context every database test shares (ADR-0012): the real application, against the
 * suite's singleton Postgres, connected as the restricted request-path role. Subclasses add no
 * properties, no mocked beans and no {@code @DirtiesContext}, because each of those is a second
 * context and a second application startup.
 */
@SpringBootTest(properties = {
        "NOSTRO_APP_PASSWORD=app-secret",
        "NOSTRO_CONTROL_PASSWORD=control-secret",
        "NOSTRO_CONTROL_KEY=" + LedgerIntegrationTest.CONTROL_KEY,
        "NOSTRO_JWT_SECRET=a-test-only-signing-secret-of-at-least-32-bytes",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@AutoConfigureMockMvc
public abstract class LedgerIntegrationTest {

    static final PostgreSQLContainer POSTGRES = LedgerPostgres.instance();

    /** The bootstrap credential the control plane is configured with, for this suite only (ADR-0015). */
    protected static final String CONTROL_KEY = "nc_test-only-bootstrap-key-of-at-least-32-bytes";

    @DynamicPropertySource
    static void ledgerDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @Autowired
    protected DataSource dataSource;

    @Autowired
    protected PlatformTransactionManager transactionManager;

    @Autowired
    protected MockMvc http;

    @Autowired
    protected JsonMapper json;

    @Autowired
    protected EntryRecorder recorder;

    /** The owner: the container's superuser, which bypasses every policy. For fixtures only. */
    protected JdbcClient asOwner() {
        var owner = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        return JdbcClient.create(owner);
    }

    /** Creates a Tenant directly, as the owner: a fixture, not the control plane, which {@code ControlPlaneIT} exercises. */
    protected TenantId newTenant() {
        var id = TenantId.random();
        asOwner().sql("INSERT INTO tenant (id, name) VALUES (?, ?)")
                .param(id.value()).param("tenant-" + id.value())
                .update();
        return id;
    }

    /** Issues an API key for the Tenant directly, as the owner, and returns the key itself. A fixture; the control plane is tested on its own. */
    protected ApiKey newApiKey(TenantId tenant, Permission... permissions) {
        var key = ApiKey.generate();
        asOwner().sql("INSERT INTO api_key (tenant_id, id, key_hash, label, permissions) VALUES (?, ?, ?, ?, ?)")
                .params(tenant.value(), uuid(), key.hash(), "test", names(permissions))
                .update();
        return key;
    }

    /** Creates a staff user directly, as the owner, with the password given; the hash is BCrypt, as the application checks. */
    protected UUID newStaffUser(TenantId tenant, String username, String password, Permission... permissions) {
        var id = uuid();
        asOwner().sql("INSERT INTO staff_user (tenant_id, id, username, password_hash, permissions) VALUES (?, ?, ?, ?, ?)")
                .params(tenant.value(), id, username, new BCryptPasswordEncoder(4).encode(password), names(permissions))
                .update();
        return id;
    }

    protected void revokeApiKey(ApiKey key) {
        asOwner().sql("UPDATE api_key SET revoked_at = now() WHERE key_hash = ?").param(key.hash()).update();
    }

    protected void revokeStaffUser(UUID id) {
        asOwner().sql("UPDATE staff_user SET revoked_at = now() WHERE id = ?").param(id).update();
    }

    private static String[] names(Permission... permissions) {
        return java.util.Arrays.stream(permissions).map(Enum::name).toArray(String[]::new);
    }

    /** Runs work in one transaction as the request-path role, acting for the Tenant given. */
    protected <T> T inTransactionAs(TenantId tenant, Supplier<T> work) {
        return TenantContext.runAs(tenant, () -> new TransactionTemplate(transactionManager).execute(status -> work.get()));
    }

    protected void inTransactionAs(TenantId tenant, Runnable work) {
        inTransactionAs(tenant, () -> {
            work.run();
            return null;
        });
    }

    /** Runs work in one transaction as the request-path role with no Tenant bound. */
    protected <T> T inTransactionWithoutTenant(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    /** A JdbcClient on the request-path DataSource; inside a transaction it uses that transaction's connection. */
    protected JdbcClient jdbc() {
        return JdbcClient.create(dataSource);
    }

    /** Opens an Account over HTTP with the {@code Authorization} value given, and returns its id. */
    protected UUID openAccount(String authorization, String code) throws Exception {
        MvcResult result = http.perform(post("/accounts").header(HttpHeaders.AUTHORIZATION, authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "%s", "currency": "USD", "constrained": false}
                                """.formatted(code)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("id").asString());
    }

    protected UUID openAccount(ApiKey key, String code) throws Exception {
        return openAccount(bearer(key), code);
    }

    /**
     * Records an Entry moving {@code minor} USD cents from one Account to another, through the real
     * recorder, acting for the Tenant: a fixture for the read tests, which need history to read.
     * Fails the test if the Entry is refused.
     */
    protected Recorded recordEntry(TenantId tenant, AccountId from, AccountId to, long minor) {
        var usd = Currency.of("USD");
        return recordEntry(tenant, List.of(new Posting(from, Money.ofMinor(-minor, usd)), new Posting(to, Money.ofMinor(minor, usd))));
    }

    protected Recorded recordEntry(TenantId tenant, List<Posting> postings) {
        var command = new RecordEntry(new IdempotencyKey(UUID.randomUUID().toString()), postings, null);
        var outcome = TenantContext.runAs(tenant, () -> recorder.record(command));
        if (!(outcome instanceof Recorded recorded)) {
            throw new AssertionError("expected the fixture Entry to be recorded, got " + outcome);
        }
        return recorded;
    }

    /** Logs a staff user in over HTTP and returns the token issued. */
    protected String login(String username, String password) throws Exception {
        MvcResult result = http.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "%s", "password": "%s"}
                                """.formatted(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("token").asString();
    }

    protected static String bearer(ApiKey key) {
        return "Bearer " + key.value();
    }

    /** The {@code Authorization} value of the control plane's bootstrap credential. */
    protected static String controlBearer() {
        return "Bearer " + CONTROL_KEY;
    }

    protected static UUID uuid() {
        return UUID.randomUUID();
    }

    /** The five-character SQLSTATE at the root of a failure, or null if there is no SQLException. */
    protected static String sqlState(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
