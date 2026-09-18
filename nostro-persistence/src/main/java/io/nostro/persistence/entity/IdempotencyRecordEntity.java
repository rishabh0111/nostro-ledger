package io.nostro.persistence.entity;

import io.nostro.domain.EntryId;
import io.nostro.domain.IdempotencyKey;
import io.nostro.domain.TenantId;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * The record behind an Idempotency Key: what the key was presented with, and what it produced.
 * Written in the Entry's transaction under the unique {@code (tenant_id, idempotency_key)} index,
 * which is the whole concurrency control (ADR-0005).
 */
@Entity
@Immutable
@Table(name = "idempotency_record")
public class IdempotencyRecordEntity {

    @Embeddable
    public record Id(
            @Column(name = "tenant_id", nullable = false) UUID tenantId,
            @Column(name = "idempotency_key", nullable = false) String idempotencyKey)
            implements Serializable {

        public static Id of(TenantId tenant, IdempotencyKey key) {
            return new Id(tenant.value(), key.value());
        }
    }

    @EmbeddedId
    private Id id;

    @Column(name = "request_fingerprint", nullable = false, updatable = false)
    private String requestFingerprint;

    @Column(name = "entry_id", nullable = false, updatable = false)
    private UUID entryId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected IdempotencyRecordEntity() {
    }

    public IdempotencyRecordEntity(TenantId tenant, IdempotencyKey key, String requestFingerprint, EntryId entry) {
        this.id = Id.of(tenant, key);
        this.requestFingerprint = requestFingerprint;
        this.entryId = entry.value();
    }

    public Id getId() {
        return id;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public EntryId entryId() {
        return new EntryId(entryId);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
