package io.nostro.persistence.entity;

import io.nostro.domain.AccountId;
import io.nostro.domain.EntryId;
import io.nostro.domain.Posting;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * A Posting row. Insert-only, like {@link EntryEntity}.
 *
 * <p>The composite foreign key to {@code entry} shares {@code tenant_id} with the primary key, and
 * JPA allows one writer per column. The mapping the Hibernate 6 research recommends — mute only
 * the shared column inside the {@code @JoinColumns} — is refused at bootstrap by ORM 7
 * ({@code AnnotationException: Column mappings for property 'entry' mix insertable with
 * 'insertable=false'}). So the association is read-only in both columns and exists for navigation;
 * the writable copies are the {@code @EmbeddedId} for {@code tenant_id} and a plain
 * {@link #entryId} column for {@code entry_id}. Both are derived from the Entry in the one
 * constructor, so they cannot diverge — and if they ever did, the composite foreign key and the
 * RLS {@code WITH CHECK} would refuse the row (docs/research/hibernate-vs-the-schema.md section 1).
 *
 * <p>The Account is a plain column, not an association: its foreign key is three columns wide,
 * two of them shared, and the database holds the constraint either way.
 */
@Entity
@Immutable
@Table(name = "posting")
public class PostingEntity {

    @EmbeddedId
    private TenantScopedId id;

    @Column(name = "entry_id", nullable = false, updatable = false)
    private UUID entryId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
            @JoinColumn(name = "tenant_id", referencedColumnName = "tenant_id", insertable = false, updatable = false),
            @JoinColumn(name = "entry_id", referencedColumnName = "id", insertable = false, updatable = false)
    })
    private EntryEntity entry;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    private EmbeddedMoney amount;

    protected PostingEntity() {
    }

    /** The only way to build one: the Tenant and the Entry id come from the Entry, never from the caller. */
    public PostingEntity(EntryEntity entry, Posting posting) {
        this.id = new TenantScopedId(entry.getId().tenantId(), UUID.randomUUID());
        this.entryId = entry.getId().id();
        this.entry = entry;
        this.accountId = posting.account().value();
        this.amount = EmbeddedMoney.of(posting.amount());
    }

    public TenantScopedId getId() {
        return id;
    }

    public EntryId entryId() {
        return new EntryId(entryId);
    }

    public EntryEntity getEntry() {
        return entry;
    }

    public AccountId accountId() {
        return new AccountId(accountId);
    }

    public EmbeddedMoney getAmount() {
        return amount;
    }

    public Posting toPosting() {
        return new Posting(accountId(), amount.toMoney());
    }
}
