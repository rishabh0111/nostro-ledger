package io.nostro.persistence.entity;

import io.nostro.domain.TenantId;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;

/**
 * The {@code (tenant_id, id)} primary key every tenant-scoped table carries. A record, as Hibernate
 * recommends for id classes: {@code equals}/{@code hashCode} for free, {@code Serializable} because
 * the specification still requires it (docs/research/hibernate-vs-the-schema.md section 2).
 *
 * <p>Column order matters for record embeddables (the HHH-18445 bug class): components are declared
 * in the same order as the columns, and both are UUIDs, so a transposition would not go unnoticed.
 */
@Embeddable
public record TenantScopedId(
        @Column(name = "tenant_id", nullable = false) UUID tenantId,
        @Column(name = "id", nullable = false) UUID id)
        implements Serializable {

    public static TenantScopedId of(TenantId tenant, UUID id) {
        return new TenantScopedId(tenant.value(), id);
    }
}
