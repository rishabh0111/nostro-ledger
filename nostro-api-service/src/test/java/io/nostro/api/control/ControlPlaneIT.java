package io.nostro.api.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nostro.api.LedgerIntegrationTest;
import io.nostro.api.problem.ProblemType;
import io.nostro.api.auth.Permission;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * The control plane is a separate authority (ADR-0015), tested at the HTTP seam (ADR-0012). The
 * two surfaces of the system refuse each other's credentials, in both directions.
 */
class ControlPlaneIT extends LedgerIntegrationTest {

    @Test
    @DisplayName("a ledger credential cannot reach a control-plane endpoint")
    void aLedgerCredentialCannotReachTheControlPlane() throws Exception {
        var tenant = newTenant();
        var ledgerKey = newApiKey(tenant, Permission.LEDGER_READ, Permission.LEDGER_WRITE);

        http.perform(post("/control/tenants").header(HttpHeaders.AUTHORIZATION, bearer(ledgerKey))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "never"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://nostro.dev/problems/forbidden"));
        // Nor its own Tenant's credentials: the Tenant a ledger key acts for is not one it administers.
        http.perform(post("/control/tenants/{tenant}/api-keys", tenant.value()).header(HttpHeaders.AUTHORIZATION, bearer(ledgerKey))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label": "never", "permissions": ["LEDGER_READ"]}
                                """))
                .andExpect(status().isForbidden());
        http.perform(delete("/control/tenants/{tenant}/api-keys/{id}", tenant.value(), uuid()).header(HttpHeaders.AUTHORIZATION, bearer(ledgerKey)))
                .andExpect(status().isForbidden());
        http.perform(post("/control/tenants/{tenant}/staff-users", tenant.value()).header(HttpHeaders.AUTHORIZATION, bearer(ledgerKey))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "never", "password": "correct horse", "permissions": ["LEDGER_READ"]}
                                """))
                .andExpect(status().isForbidden());
        http.perform(delete("/control/tenants/{tenant}/staff-users/{id}", tenant.value(), uuid()).header(HttpHeaders.AUTHORIZATION, bearer(ledgerKey)))
                .andExpect(status().isForbidden());
        assertThat(asOwner().sql("SELECT count(*) FROM tenant WHERE name = 'never'").query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("a control-plane credential cannot reach a ledger endpoint")
    void aControlCredentialCannotReachTheLedger() throws Exception {
        http.perform(get("/accounts/{id}", uuid()).header(HttpHeaders.AUTHORIZATION, controlBearer()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://nostro.dev/problems/forbidden"));
        http.perform(post("/accounts").header(HttpHeaders.AUTHORIZATION, controlBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "never", "currency": "USD", "constrained": false}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a Tenant created and a key issued through the control plane is a ledger caller, and still sees no other Tenant")
    void theControlPlaneMakesLedgerCallers() throws Exception {
        var a = createTenant("tenant-" + uuid());
        var b = createTenant("tenant-" + uuid());
        var keyOfA = issueApiKey(a, "integration", "LEDGER_READ", "LEDGER_WRITE").key();
        var keyOfB = issueApiKey(b, "integration", "LEDGER_READ", "LEDGER_WRITE").key();
        var accountOfB = openAccount("Bearer " + keyOfB, "cash");

        http.perform(get("/accounts/{id}", accountOfB).header(HttpHeaders.AUTHORIZATION, "Bearer " + keyOfB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("cash"));
        http.perform(get("/accounts/{id}", accountOfB).header(HttpHeaders.AUTHORIZATION, "Bearer " + keyOfA))
                .andExpect(problem(ProblemType.UNKNOWN_ACCOUNT));
    }

    @Test
    @DisplayName("the key is shown once, at issue; the response otherwise describes the credential without it")
    void theKeyIsShownOnce() throws Exception {
        var tenant = createTenant("tenant-" + uuid());

        var issued = issueApiKey(tenant, "billing", "LEDGER_READ");

        assertThat(issued.key()).startsWith("nk_");
        assertThat(issued.body().get("tenantId").asString()).isEqualTo(tenant.toString());
        assertThat(issued.body().get("label").asString()).isEqualTo("billing");
        assertThat(issued.body().get("permissions").size()).isEqualTo(1);
        assertThat(issued.body().get("permissions").get(0).asString()).isEqualTo("LEDGER_READ");
        assertThat(asOwner().sql("SELECT key_hash FROM api_key WHERE id = ?").param(issued.id()).query(String.class).single())
                .as("what is stored is a hash, not the key")
                .isNotEqualTo(issued.key()).hasSize(64);
    }

    @Test
    @DisplayName("a key revoked through the control plane is refused on its next request")
    void aRevokedKeyIsRefusedAtOnce() throws Exception {
        var tenant = createTenant("tenant-" + uuid());
        var issued = issueApiKey(tenant, "short-lived", "LEDGER_READ");

        http.perform(get("/accounts/{id}", uuid()).header(HttpHeaders.AUTHORIZATION, "Bearer " + issued.key()))
                .andExpect(status().isNotFound());
        http.perform(delete("/control/tenants/{tenant}/api-keys/{id}", tenant, issued.id())
                        .header(HttpHeaders.AUTHORIZATION, controlBearer()))
                .andExpect(status().isNoContent());
        http.perform(get("/accounts/{id}", uuid()).header(HttpHeaders.AUTHORIZATION, "Bearer " + issued.key()))
                .andExpect(status().isUnauthorized());
        // Revoking again changes nothing and is not an error; an unknown key is.
        http.perform(delete("/control/tenants/{tenant}/api-keys/{id}", tenant, issued.id())
                        .header(HttpHeaders.AUTHORIZATION, controlBearer()))
                .andExpect(status().isNoContent());
        http.perform(delete("/control/tenants/{tenant}/api-keys/{id}", tenant, uuid())
                        .header(HttpHeaders.AUTHORIZATION, controlBearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the control plane issues ledger Permissions only; CONTROL is refused by the control plane and by the schema")
    void controlIsNeverAStoredPermission() throws Exception {
        var tenant = createTenant("tenant-" + uuid());

        http.perform(post("/control/tenants/{tenant}/api-keys", tenant).header(HttpHeaders.AUTHORIZATION, controlBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label": "never", "permissions": ["CONTROL"]}
                                """))
                .andExpect(status().isBadRequest());
        http.perform(post("/control/tenants/{tenant}/api-keys", tenant).header(HttpHeaders.AUTHORIZATION, controlBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label": "never", "permissions": []}
                                """))
                .andExpect(status().isBadRequest());

        var apiKeyRow = catchThrowable(() -> asOwner()
                .sql("INSERT INTO api_key (tenant_id, id, key_hash, label, permissions) VALUES (?, ?, ?, ?, ?)")
                .params(tenant, uuid(), "0".repeat(64), "never", new String[] {"LEDGER_READ", "CONTROL"})
                .update());
        var staffRow = catchThrowable(() -> asOwner()
                .sql("INSERT INTO staff_user (tenant_id, id, username, password_hash, permissions) VALUES (?, ?, ?, ?, ?)")
                .params(tenant, uuid(), "never-" + uuid(), "x", new String[] {"CONTROL"})
                .update());

        assertThat(sqlState(apiKeyRow)).isEqualTo("23514");
        assertThat(apiKeyRow).hasMessageContaining("api_key_holds_ledger_permissions");
        assertThat(sqlState(staffRow)).isEqualTo("23514");
        assertThat(staffRow).hasMessageContaining("staff_user_holds_ledger_permissions");
    }

    @Test
    @DisplayName("an unknown Tenant is 404, a taken name is 409, an unknown permission is 400, each with its own type")
    void theControlPlaneRefusesWhatItCannotDo() throws Exception {
        var name = "tenant-" + uuid();
        createTenant(name);

        http.perform(post("/control/tenants").header(HttpHeaders.AUTHORIZATION, controlBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ControlPlaneController.CreateTenant(name))))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(ProblemType.TENANT_NAME_TAKEN.uri().toString()));
        http.perform(post("/control/tenants/{tenant}/api-keys", uuid()).header(HttpHeaders.AUTHORIZATION, controlBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label": "orphan", "permissions": ["LEDGER_READ"]}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(ProblemType.UNKNOWN_TENANT.uri().toString()));
        http.perform(post("/control/tenants/{tenant}/api-keys", uuid()).header(HttpHeaders.AUTHORIZATION, controlBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label": "typo", "permissions": ["LEDGER_ADMIN"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(ProblemType.UNKNOWN_PERMISSION.uri().toString()));
    }

    @Test
    @DisplayName("a control key that is not the configured one is 401, like any other bad credential")
    void aWrongControlKeyIsUnauthenticated() throws Exception {
        http.perform(post("/control/tenants").header(HttpHeaders.AUTHORIZATION, "Bearer nc_" + "wrong".repeat(8))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "never"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://nostro.dev/problems/unauthenticated"));
        assertThat(asOwner().sql("SELECT count(*) FROM tenant WHERE name = 'never'").query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("the control plane connects as nostro_control, not as the request-path role")
    void theControlPlaneConnectsAsItsOwnRole() throws Exception {
        createTenant("tenant-" + uuid());

        var roles = asOwner().sql("SELECT DISTINCT usename FROM pg_stat_activity WHERE application_name = 'nostro-control'")
                .query(String.class).list();

        assertThat(roles).containsExactly("nostro_control");
    }

    @Test
    @DisplayName("a staff user created through the control plane logs in; revoked, their token stops working at once")
    void theControlPlaneMakesStaffUsers() throws Exception {
        var tenant = createTenant("tenant-" + uuid());
        var username = "staff-" + uuid();

        MvcResult created = http.perform(post("/control/tenants/{tenant}/staff-users", tenant)
                        .header(HttpHeaders.AUTHORIZATION, controlBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ControlPlaneController.CreateStaffUser(
                                username, "correct horse", List.of("LEDGER_READ", "LEDGER_WRITE")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.tenantId").value(tenant.toString()))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andReturn();
        var staffUser = UUID.fromString(json.readTree(created.getResponse().getContentAsString()).get("id").asString());

        var token = login(username, "correct horse");
        var account = openAccount("Bearer " + token, "staff-opened");
        http.perform(get("/accounts/{id}", account).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        http.perform(delete("/control/tenants/{tenant}/staff-users/{id}", tenant, staffUser)
                        .header(HttpHeaders.AUTHORIZATION, controlBearer()))
                .andExpect(status().isNoContent());
        http.perform(get("/accounts/{id}", account).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
        http.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "%s", "password": "correct horse"}
                                """.formatted(username)))
                .andExpect(status().isUnauthorized());
    }

    private UUID createTenant(String name) throws Exception {
        MvcResult result = http.perform(post("/control/tenants").header(HttpHeaders.AUTHORIZATION, controlBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ControlPlaneController.CreateTenant(name))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(name))
                .andReturn();
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("id").asString());
    }

    private IssuedKey issueApiKey(UUID tenant, String label, String... permissions) throws Exception {
        MvcResult result = http.perform(post("/control/tenants/{tenant}/api-keys", tenant)
                        .header(HttpHeaders.AUTHORIZATION, controlBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ControlPlaneController.IssueApiKey(label, List.of(permissions)))))
                .andExpect(status().isCreated())
                .andReturn();
        var body = json.readTree(result.getResponse().getContentAsString());
        return new IssuedKey(UUID.fromString(body.get("id").asString()), body.get("key").asString(), body);
    }

    private record IssuedKey(UUID id, String key, JsonNode body) {
    }
}
