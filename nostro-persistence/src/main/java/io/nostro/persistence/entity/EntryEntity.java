package io.nostro.persistence.entity;

import io.nostro.domain.EntryId;
import io.nostro.domain.TenantId;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * An Entry row. Insert-only. {@code @Immutable} is a declaration of intent and a dirty-checking
 * saving; it silently ignores updates and says nothing about deletes, so the barrier is the
 * revoked grants on the request-path role (docs/research/hibernate-vs-the-schema.md section 4).
 *
 * <p>{@code position} is not mapped: it is an {@code xid8} assigned by the database inside the
 * recording transaction, and the writer reads it back with a native query.
 */
@Entity
@Immutable
@Table(name = "entry")
public class EntryEntity {

    @EmbeddedId
    private TenantScopedId id;

    @Column(name = "reverses_entry_id", updatable = false)
    private UUID reversesEntryId;

    @Column(name = "description", updatable = false)
    private String description;

    @Column(name = "recorded_at", nullable = false, insertable = false, updatable = false)
    private Instant recordedAt;

    protected EntryEntity() {
    }

    public EntryEntity(TenantId tenant, EntryId entry, Optional<EntryId> reverses, String description) {
        this.id = TenantScopedId.of(tenant, entry.value());
        this.reversesEntryId = reverses.map(EntryId::value).orElse(null);
        this.description = description;
    }

    public TenantScopedId getId() {
        return id;
    }

    public EntryId entryId() {
        return new EntryId(id.id());
    }

    public Optional<EntryId> reverses() {
        return Optional.ofNullable(reversesEntryId).map(EntryId::new);
    }

    public String getDescription() {
        return description;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}
