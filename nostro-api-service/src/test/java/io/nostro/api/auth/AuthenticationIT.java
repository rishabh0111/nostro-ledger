package io.nostro.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nostro.api.LedgerIntegrationTest;
import io.nostro.persistence.tenant.TenantContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * The credential is the only thing a request's Tenant is derived from (ADR-0006), tested at the
 * HTTP seam (ADR-0012): a valid credential for one Tenant, a request naming another's Account, and
 * nothing either way.
 */
class AuthenticationIT extends LedgerIntegrationTest {

    @Autowired
    AuthProperties properties;

    @Autowired
    RequiredPermissions requiredPermissions;

    @Test
    @DisplayName("a valid credential for one Tenant, used on a request naming another Tenant's Account, returns nothing")
    void anotherTenantsAccountIsAbsentNotForbidden() throws Exception {
        var a = newTenant();
        var b = newTenant();
        var keyOfA = newApiKey(a, Permission.LEDGER_READ, Permission.LEDGER_WRITE);
        var keyOfB = newApiKey(b, Permission.LEDGER_READ, Permission.LEDGER_WRITE);
        var accountOfB = openAccount(keyOfB, "cash");

        http.perform(get("/accounts/{id}", accountOfB).header(HttpHeaders.AUTHORIZATION, bearer(keyOfB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(accountOfB.toString()))
                .andExpect(jsonPath("$.code").value("cash"));
        http.perform(get("/accounts/{id}", accountOfB).header(HttpHeaders.AUTHORIZATION, bearer(keyOfA)))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("no credential is 401, whatever is asked for")
    void noCredentialIsUnauthenticated() throws Exception {
        http.perform(get("/accounts/{id}", uuid()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://nostro.dev/problems/unauthenticated"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("an unknown, malformed or revoked API key is 401")
    void badApiKeysAreUnauthenticated() throws Exception {
        var tenant = newTenant();
        var revoked = newApiKey(tenant, Permission.LEDGER_READ);
        revokeApiKey(revoked);

        http.perform(get("/accounts/{id}", uuid()).header(HttpHeaders.AUTHORIZATION, bearer(ApiKey.generate())))
                .andExpect(status().isUnauthorized());
        http.perform(get("/accounts/{id}", uuid()).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-credential"))
                .andExpect(status().isUnauthorized());
        http.perform(get("/accounts/{id}", uuid()).header(HttpHeaders.AUTHORIZATION, bearer(revoked)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a credential without the Permission an endpoint requires is 403, and the endpoint does not run")
    void aMissingPermissionIsForbidden() throws Exception {
        var tenant = newTenant();
        var readOnly = newApiKey(tenant, Permission.LEDGER_READ);

        http.perform(post("/accounts").header(HttpHeaders.AUTHORIZATION, bearer(readOnly))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "never", "currency": "USD", "constrained": false}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://nostro.dev/problems/forbidden"));
        assertThat(asOwner().sql("SELECT count(*) FROM account WHERE tenant_id = ? AND code = 'never'")
                .param(tenant.value()).query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("staff log in with a password and get a short-lived token that works like an API key")
    void staffLogInForAToken() throws Exception {
        var tenant = newTenant();
        var username = "staff-" + uuid();
        newStaffUser(tenant, username, "correct horse", Permission.LEDGER_READ, Permission.LEDGER_WRITE);

        var token = login(username, "correct horse");
        var account = openAccount("Bearer " + token, "staff-opened");

        http.perform(get("/accounts/{id}", account).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("staff-opened"));
    }

    @Test
    @DisplayName("a wrong password, or an unknown username, is 401 and issues nothing")
    void wrongPasswordsIssueNothing() throws Exception {
        var tenant = newTenant();
        var username = "staff-" + uuid();
        newStaffUser(tenant, username, "correct horse", Permission.LEDGER_READ);

        http.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new LoginController.Login(username, "battery staple"))))
                .andExpect(status().isUnauthorized());
        http.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new LoginController.Login("nobody-" + uuid(), "correct horse"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an expired token, a forged token, and a token for a revoked staff user are all 401")
    void staleTokensAreUnauthenticated() throws Exception {
        var tenant = newTenant();
        var username = "staff-" + uuid();
        var user = newStaffUser(tenant, username, "correct horse", Permission.LEDGER_READ);
        var token = login(username, "correct horse");

        var expired = new StaffTokens(properties, Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC))
                .issue(user).token();
        var forged = new StaffTokens(new AuthProperties("another-secret-that-is-also-32-bytes-long", properties.jwtTtl(), properties.controlKey()),
                Clock.systemUTC()).issue(user).token();

        http.perform(get("/accounts/{id}", uuid()).header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isUnauthorized());
        http.perform(get("/accounts/{id}", uuid()).header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
                .andExpect(status().isUnauthorized());
        http.perform(get("/accounts/{id}", uuid()).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound());
        revokeStaffUser(user);
        http.perform(get("/accounts/{id}", uuid()).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the Tenant is bound for the request and unbound after it, whatever the outcome")
    void theTenantIsUnboundAfterTheRequest() throws Exception {
        var tenant = newTenant();
        var key = newApiKey(tenant, Permission.LEDGER_READ);

        http.perform(get("/accounts/{id}", uuid()).header(HttpHeaders.AUTHORIZATION, bearer(key)))
                .andExpect(status().isNotFound());
        assertThat(TenantContext.current()).as("after a served request").isEmpty();
        http.perform(get("/accounts/{id}", "not-a-uuid").header(HttpHeaders.AUTHORIZATION, bearer(key)))
                .andExpect(status().isBadRequest());
        assertThat(TenantContext.current()).as("after a request that failed inside the handler").isEmpty();
    }

    @Test
    @DisplayName("the startup check ran against the real endpoints; the unit test proves it bites")
    void theStartupCheckSawEveryEndpoint() {
        assertThat(requiredPermissions.verifiedEndpoints())
                .contains("AccountsController.open", "AccountsController.find", "LoginController.login",
                        "BalanceController.read", "AccountHistoryController.newestFirst",
                        "ControlPlaneController.createTenant", "ControlPlaneController.issueApiKey",
                        "ControlPlaneController.revokeApiKey", "ControlPlaneController.createStaffUser",
                        "ControlPlaneController.revokeStaffUser");
    }

    @Test
    @DisplayName("a request the framework itself refuses is a Problem Details body too")
    void frameworkRefusalsAreProblemDetails() throws Exception {
        var key = newApiKey(newTenant(), Permission.LEDGER_READ);

        http.perform(get("/accounts/{id}", "not-a-uuid").header(HttpHeaders.AUTHORIZATION, bearer(key)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
    }
}
