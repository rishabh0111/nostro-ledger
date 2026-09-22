package io.nostro.api.control;

import io.nostro.api.auth.ApiKey;
import io.nostro.api.auth.Permission;
import io.nostro.domain.TenantId;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * What the control plane does: creates Tenants, issues credentials, revokes them (ADR-0015). The
 * one place in the system that legitimately writes across Tenants, connected as {@code
 * nostro_control} through {@link ControlPlaneDatabase}. Refusals are values, not exceptions
 * (ADR-0014).
 *
 * <p>A credential it issues holds ledger Permissions only. {@link Permission#CONTROL} is the
 * bootstrap credential's and is never stored; the schema refuses the row as well.
 */
@Component
public class ControlPlane {

    /** What a stored credential may hold: every Permission but the control plane's own. */
    static final Set<Permission> ISSUABLE = Set.copyOf(EnumSet.complementOf(EnumSet.of(Permission.CONTROL)));

    private final ControlPlaneDatabase database;
    private final PasswordEncoder passwords;

    ControlPlane(ControlPlaneDatabase database, PasswordEncoder passwords) {
        this.database = database;
        this.passwords = passwords;
    }

    public TenantOutcome createTenant(String name) {
        if (name == null || name.isBlank() || name.length() > 100) {
            return new TenantOutcome.Invalid("a Tenant's name is between 1 and 100 characters");
        }
        var id = TenantId.random();
        try {
            database.jdbc().sql("INSERT INTO tenant (id, name) VALUES (:id, :name)")
                    .param("id", id.value()).param("name", name)
                    .update();
        } catch (DuplicateKeyException taken) {
            return new TenantOutcome.NameTaken(name);
        }
        return new TenantOutcome.Created(new Tenant(id, name));
    }

    Optional<Tenant> findTenant(String name) {
        return database.jdbc().sql("SELECT id, name FROM tenant WHERE name = :name")
                .param("name", name)
                .query((rs, row) -> new Tenant(new TenantId(rs.getObject("id", UUID.class)), rs.getString("name")))
                .optional();
    }

    /** Issues an API key for the Tenant. The key itself is in the outcome and nowhere else, ever. */
    public IssueOutcome issueApiKey(TenantId tenant, String label, Collection<Permission> permissions) {
        if (label == null || label.isBlank() || label.length() > 100) {
            return new IssueOutcome.Invalid("a label is between 1 and 100 characters");
        }
        var refused = refusedPermissions(permissions);
        if (refused.isPresent()) {
            return new IssueOutcome.Invalid(refused.get());
        }
        if (!tenantExists(tenant)) {
            return new IssueOutcome.UnknownTenant(tenant);
        }
        var key = ApiKey.generate();
        var id = UUID.randomUUID();
        database.jdbc().sql("""
                        INSERT INTO api_key (tenant_id, id, key_hash, label, permissions)
                        VALUES (:tenant, :id, :hash, :label, :permissions)
                        """)
                .param("tenant", tenant.value()).param("id", id).param("hash", key.hash())
                .param("label", label).param("permissions", names(permissions))
                .update();
        return new IssueOutcome.Issued(new IssuedApiKey(id, tenant, label, Set.copyOf(permissions), key));
    }

    /**
     * Revokes the key, which refuses its next request. Revoking a revoked key changes nothing and
     * is not an error; the row is kept, with when it was revoked.
     */
    public RevokeOutcome revokeApiKey(TenantId tenant, UUID id) {
        return revoke("api_key", tenant, id);
    }

    /** Creates a staff user who logs in with the password given; only its hash is stored. */
    public StaffOutcome createStaffUser(TenantId tenant, String username, String password, Collection<Permission> permissions) {
        if (username == null || username.isBlank() || username.length() > 100) {
            return new StaffOutcome.Invalid("a username is between 1 and 100 characters");
        }
        if (password == null || password.length() < 8) {
            return new StaffOutcome.Invalid("a password is at least 8 characters");
        }
        var refused = refusedPermissions(permissions);
        if (refused.isPresent()) {
            return new StaffOutcome.Invalid(refused.get());
        }
        if (!tenantExists(tenant)) {
            return new StaffOutcome.UnknownTenant(tenant);
        }
        var id = UUID.randomUUID();
        try {
            database.jdbc().sql("""
                            INSERT INTO staff_user (tenant_id, id, username, password_hash, permissions)
                            VALUES (:tenant, :id, :username, :hash, :permissions)
                            """)
                    .param("tenant", tenant.value()).param("id", id).param("username", username)
                    .param("hash", passwords.encode(password)).param("permissions", names(permissions))
                    .update();
        } catch (DuplicateKeyException taken) {
            return new StaffOutcome.UsernameTaken(username);
        }
        return new StaffOutcome.Created(new StaffUser(id, tenant, username, Set.copyOf(permissions)));
    }

    /** Revokes the staff user: their next login, and every token they hold, is refused at once. */
    public RevokeOutcome revokeStaffUser(TenantId tenant, UUID id) {
        return revoke("staff_user", tenant, id);
    }

    /** The two credential tables share a shape; the table name is one of two literals, never input. */
    private RevokeOutcome revoke(String table, TenantId tenant, UUID id) {
        int rows = database.jdbc().sql("UPDATE " + table + " SET revoked_at = coalesce(revoked_at, now())"
                        + " WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", tenant.value()).param("id", id)
                .update();
        return rows == 0 ? new RevokeOutcome.Unknown(id) : new RevokeOutcome.Revoked(id);
    }

    private boolean tenantExists(TenantId tenant) {
        return database.jdbc().sql("SELECT count(*) FROM tenant WHERE id = :id")
                .param("id", tenant.value())
                .query(Long.class).single() > 0;
    }

    /** Why the Permissions asked for cannot be stored, or empty if they can. */
    private static Optional<String> refusedPermissions(Collection<Permission> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return Optional.of("a credential holds at least one Permission");
        }
        if (!ISSUABLE.containsAll(permissions)) {
            return Optional.of("a stored credential holds ledger Permissions only: " + ISSUABLE);
        }
        return Optional.empty();
    }

    private static String[] names(Collection<Permission> permissions) {
        return permissions.stream().map(Enum::name).distinct().toArray(String[]::new);
    }

    public record Tenant(TenantId id, String name) {
    }

    /** A freshly issued key: the only time {@link #key} is available in the clear. */
    public record IssuedApiKey(UUID id, TenantId tenant, String label, Set<Permission> permissions, ApiKey key) {
    }

    public record StaffUser(UUID id, TenantId tenant, String username, Set<Permission> permissions) {
    }

    public sealed interface TenantOutcome {
        record Created(Tenant tenant) implements TenantOutcome {
        }

        record NameTaken(String name) implements TenantOutcome {
        }

        record Invalid(String reason) implements TenantOutcome {
        }
    }

    public sealed interface IssueOutcome {
        record Issued(IssuedApiKey apiKey) implements IssueOutcome {
        }

        record UnknownTenant(TenantId tenant) implements IssueOutcome {
        }

        record Invalid(String reason) implements IssueOutcome {
        }
    }

    public sealed interface StaffOutcome {
        record Created(StaffUser staffUser) implements StaffOutcome {
        }

        record UnknownTenant(TenantId tenant) implements StaffOutcome {
        }

        record UsernameTaken(String username) implements StaffOutcome {
        }

        record Invalid(String reason) implements StaffOutcome {
        }
    }

    public sealed interface RevokeOutcome {
        record Revoked(UUID id) implements RevokeOutcome {
        }

        record Unknown(UUID id) implements RevokeOutcome {
        }
    }
}
