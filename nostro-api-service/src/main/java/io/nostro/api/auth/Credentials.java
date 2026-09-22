package io.nostro.api.auth;

import io.nostro.domain.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The stored credentials, read to learn which Tenant a request acts for (ADR-0006). These queries
 * run before there is a Tenant and outside any transaction, on tables that carry no row-level
 * security for exactly that reason (see {@code V2__credentials.sql}). A revoked credential is
 * absent here, whatever the row still says.
 */
@Component
public class Credentials {

    private final JdbcClient jdbc;

    Credentials(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /** The Caller behind an API key, found by the key's hash. */
    public Optional<Caller> callerFor(ApiKey key) {
        return jdbc.sql("""
                        SELECT tenant_id, id, permissions FROM api_key
                         WHERE key_hash = :hash AND revoked_at IS NULL
                        """)
                .param("hash", key.hash())
                .query((rs, row) -> caller(Caller.Kind.API_KEY, rs))
                .optional();
    }

    /** A staff user by username, with the hash their password must match. */
    public Optional<StaffUser> staffUser(String username) {
        return jdbc.sql("""
                        SELECT tenant_id, id, password_hash, permissions FROM staff_user
                         WHERE username = :username AND revoked_at IS NULL
                        """)
                .param("username", username)
                .query((rs, row) -> new StaffUser(caller(Caller.Kind.STAFF, rs), rs.getString("password_hash")))
                .optional();
    }

    /** The Caller behind a staff token's subject, re-read on every request so that revocation takes effect at once. */
    public Optional<Caller> callerFor(UUID staffUser) {
        return jdbc.sql("""
                        SELECT tenant_id, id, permissions FROM staff_user
                         WHERE id = :id AND revoked_at IS NULL
                        """)
                .param("id", staffUser)
                .query((rs, row) -> caller(Caller.Kind.STAFF, rs))
                .optional();
    }

    private static Caller caller(Caller.Kind kind, ResultSet rs) throws SQLException {
        var tenant = new TenantId(rs.getObject("tenant_id", UUID.class));
        var id = rs.getObject("id", UUID.class);
        String[] names = (String[]) rs.getArray("permissions").getArray();
        Set<Permission> permissions = Arrays.stream(names).map(Permission::valueOf).collect(Collectors.toSet());
        return new Caller(kind, id, tenant, permissions);
    }

    public record StaffUser(Caller caller, String passwordHash) {
    }
}
