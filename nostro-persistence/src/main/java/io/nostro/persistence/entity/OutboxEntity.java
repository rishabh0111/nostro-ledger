package io.nostro.persistence.entity;

import io.nostro.domain.EntryId;
import io.nostro.domain.TenantId;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One outbox row per Entry, written in the Entry's own transaction from the very first Entry, and
 * drained into Kafka by the outbox relay, a deployable of its own (ADR-0009). {@code position} (an
 * {@code xid8}, the same value as the Entry's) is assigned by the database and not mapped. The relay
 * marks rows with {@code published_at} and {@code publish_seq}; the request path never touches those
 * columns, and holds no grant that would let it.
 */
@Entity
@Table(name = "outbox")
public class OutboxEntity {

    @EmbeddedId
    private TenantScopedId id;

    @Column(name = "entry_id", nullable = false, updatable = false)
    private UUID entryId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at", insertable = false, updatable = false)
    private Instant publishedAt;

    @Column(name = "publish_seq", insertable = false, updatable = false)
    private Long publishSeq;

    protected OutboxEntity() {
    }

    public OutboxEntity(TenantId tenant, EntryId entry, String payload) {
        this.id = TenantScopedId.of(tenant, UUID.randomUUID());
        this.entryId = entry.value();
        this.payload = payload;
    }

    public TenantScopedId getId() {
        return id;
    }

    public EntryId entryId() {
        return new EntryId(entryId);
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public Long getPublishSeq() {
        return publishSeq;
    }
}
