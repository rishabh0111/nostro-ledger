package io.nostro.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nostro.domain.AccountId;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The projection's database is a boundary of its own (ADR-0008): its own tables and nothing of the
 * ledger's, isolated per Tenant by the same technique — row-level security, a transaction-local
 * Tenant, a role that bypasses nothing.
 */
class ProjectionIsolationIT extends ProjectionIntegrationTest {

    @Test
    @DisplayName("the projection's database holds its own tables and none of the ledger's")
    void theProjectionDatabaseHoldsNoLedgerTables() {
        var tables = asOwner().sql("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'")
                .query(String.class)
                .list();

        assertThat(tables).containsExactlyInAnyOrder(
                "projected_balance", "applied_entry", "tenant_watermark", "consumer_position", "flyway_schema_history");
    }

    @Test
    @DisplayName("the service's role is not a superuser, does not bypass row-level security, and owns nothing")
    void theRuntimeRoleBypassesNothing() {
        var role = asOwner().sql("SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = 'nostro_projection'")
                .query((row, n) -> row.getBoolean(1) || row.getBoolean(2))
                .single();
        var owned = asOwner().sql("SELECT count(*) FROM pg_tables WHERE tableowner = 'nostro_projection'").query(Long.class).single();

        assertThat(role).isFalse();
        assertThat(owned).isZero();
        assertThat(asRuntimeRole().sql("SELECT current_user").query(String.class).single()).isEqualTo("nostro_projection");
    }

    @Test
    @DisplayName("a Tenant reading another's Account sees what it would see for an Account that does not exist: nothing, and its own watermark")
    void aCrossTenantReadReturnsNothing() {
        var a = newTenant();
        var b = newTenant();
        var cashOfA = AccountId.random();
        var bankOfA = AccountId.random();
        var cashOfB = AccountId.random();
        var ofA = entry(a, bankOfA, cashOfA, "USD", 5_000);
        var ofB = entry(b, AccountId.random(), cashOfB, "EUR", 10);
        publish(ofA);
        publish(ofB);
        awaitWatermark(a, ofA.createdAt());
        awaitWatermark(b, ofB.createdAt());

        var asB = balances.read(b, cashOfA);

        assertThat(asB.amountMinor()).isZero();
        assertThat(asB.currency()).isEmpty();
        assertThat(asB.watermark()).contains(ofB.createdAt());
        assertThat(balances.read(a, cashOfA).amountMinor()).isEqualTo(5_000);
        // Not only through the service's query: in B's context, the table holds B's rows and no others.
        var tenantsVisibleToB = transactions.readingAs(b, jdbc ->
                jdbc.sql("SELECT DISTINCT tenant_id FROM projected_balance").query(UUID.class).list());
        assertThat(tenantsVisibleToB).containsExactly(b.value());
    }

    @Test
    @DisplayName("with no Tenant bound, the role reads nothing and writes nothing")
    void noTenantContextFailsClosed() {
        var tenant = newTenant();
        var entry = entry(tenant, AccountId.random(), AccountId.random(), "USD", 1);
        publish(entry);
        awaitWatermark(tenant, entry.createdAt());

        JdbcClient role = asRuntimeRole();
        assertThat(role.sql("SELECT count(*) FROM projected_balance").query(Long.class).single()).isZero();
        assertThat(role.sql("SELECT count(*) FROM applied_entry").query(Long.class).single()).isZero();
        assertThat(role.sql("SELECT count(*) FROM tenant_watermark").query(Long.class).single()).isZero();
        assertThatThrownBy(() -> role.sql("INSERT INTO projected_balance VALUES (?, ?, 'USD', 1)")
                .params(tenant.value(), UUID.randomUUID()).update())
                .hasRootCauseInstanceOf(SQLException.class)
                .rootCause().hasMessageContaining("row-level security");
    }

    @Test
    @DisplayName("the service's role can neither delete a Balance nor rewrite an applied Entry")
    void appliedHistoryIsNotRewritable() {
        assertThatThrownBy(() -> asRuntimeRole().sql("DELETE FROM projected_balance").update())
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> asRuntimeRole().sql("UPDATE applied_entry SET applied_at = now()").update())
                .rootCause().hasMessageContaining("permission denied");
    }
}
