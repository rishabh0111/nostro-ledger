package io.nostro.api.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nostro.api.LedgerIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

/**
 * What {@code docker compose up} ends with (ADR-0015): two Tenants and their keys, so the refusals
 * can be shown the moment the stack is up. Seeding is off in this suite's context and is run here
 * by hand, against the same database; what it yields is checked at the HTTP seam.
 */
class DemoTenantsIT extends LedgerIntegrationTest {

    @Autowired
    DemoTenants demoTenants;

    @Test
    @DisplayName("seeding yields two Tenants whose keys work at the ledger and see nothing of each other; seeding again issues nothing")
    void twoDemoTenantsAreSeededOnce() throws Exception {
        var seeded = demoTenants.seed();

        assertThat(seeded).hasSize(2);
        assertThat(seeded).extracting(DemoTenants.Seeded::name).containsExactly(DemoTenants.NAMES.toArray(String[]::new));
        assertThat(seeded).allSatisfy(s -> assertThat(s.key()).isPresent());
        var alpha = seeded.get(0).key().orElseThrow();
        var beta = seeded.get(1).key().orElseThrow();

        var accountOfAlpha = openAccount(alpha, "cash");
        http.perform(get("/accounts/{id}", accountOfAlpha).header(HttpHeaders.AUTHORIZATION, bearer(alpha)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("cash"));
        http.perform(get("/accounts/{id}", accountOfAlpha).header(HttpHeaders.AUTHORIZATION, bearer(beta)))
                .andExpect(status().isNotFound());

        var again = demoTenants.seed();
        assertThat(again).hasSize(2);
        assertThat(again).allSatisfy(s -> assertThat(s.key()).as("an existing Tenant gets no new key").isEmpty());
        assertThat(asOwner().sql("SELECT count(*) FROM tenant WHERE name = ANY (?)")
                .param(DemoTenants.NAMES.toArray(String[]::new)).query(Long.class).single()).isEqualTo(2L);
    }
}
