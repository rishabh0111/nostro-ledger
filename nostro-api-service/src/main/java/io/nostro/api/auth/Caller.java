package io.nostro.api.auth;

import io.nostro.domain.TenantId;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Who a request is from, as established from its validated credential and nothing else
 * (ADR-0006). A ledger caller acts for exactly one Tenant; the control plane's caller acts for
 * none, which is what lets it operate across them (ADR-0015).
 */
public record Caller(Kind kind, UUID id, @Nullable TenantId boundTenant, Set<Permission> permissions) {

    public Caller {
        permissions = Set.copyOf(permissions);
    }

    public Optional<TenantId> tenant() {
        return Optional.ofNullable(boundTenant);
    }

    public boolean holds(Permission permission) {
        return permissions.contains(permission);
    }

    public enum Kind {
        API_KEY,
        STAFF,
        CONTROL
    }
}
