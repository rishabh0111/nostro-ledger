package io.nostro.persistence.ledger;

import io.nostro.domain.AccountId;
import io.nostro.domain.Currency;
import io.nostro.domain.EntryId;
import io.nostro.domain.Money;
import io.nostro.domain.Position;
import io.nostro.domain.PostingId;
import io.nostro.domain.TenantId;
import io.nostro.persistence.Installation;
import io.nostro.persistence.entity.AccountEntity;
import io.nostro.persistence.entity.TenantScopedId;
import io.nostro.persistence.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.StatelessSession;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * An Account's history: the Postings against it, newest first, a page at a time.
 *
 * <p>Paged by keyset on {@code (position, id)}, the order {@code posting_account_history_idx}
 * stores. Postings are insert-only, so a page never changes under a reader and an offset's
 * drift-and-repeat cannot happen. What keyset does not promise is that nothing appears
 * <em>behind</em> a reader: a Position is assigned at an Entry's first write, not at its commit
 * (docs/research/ordering-and-watermarks.md section 2), so an Entry that was in flight when a page
 * was cut can commit later with a Position older than that page's cursor. A reader that must see
 * it reads again from the top. This is the same property the outbox drain lives with, and it is
 * stated rather than hidden.
 */
@Component
public class AccountHistory {

    /** One Posting as the history shows it: which Entry it belongs to, and the Position that Entry created. */
    public record RecordedPosting(PostingId id, EntryId entry, Money amount, Position position) {
    }

    /**
     * Where a page ended: the last Posting on it. The next page is everything older. On the wire it
     * is a token — base64url of {@code <position token>/<posting id>} — opaque to callers, who hand
     * it back and never read it. Like a Position, a token from another installation is not a cursor
     * here, and neither is a malformed one.
     */
    public record Cursor(Position position, PostingId id) {

        public Cursor {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(id, "id");
        }

        public String token() {
            String plain = position.token() + "/" + id;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.US_ASCII));
        }

        public static Optional<Cursor> parse(String token, Installation installation) {
            try {
                String[] parts = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.US_ASCII).split("/", -1);
                if (parts.length != 2) {
                    return Optional.empty();
                }
                var id = new PostingId(UUID.fromString(parts[1]));
                return installation.parse(parts[0]).map(position -> new Cursor(position, id));
            } catch (IllegalArgumentException notBase64OrNotAUuid) {
                return Optional.empty();
            }
        }
    }

    /** A page of history, and where to ask for the next one if there is more. */
    public record Page(AccountId account, List<RecordedPosting> postings, Optional<Cursor> next) {
        public Page {
            postings = List.copyOf(postings);
        }
    }

    static final String NEWEST_FIRST = """
            SELECT p.id, p.entry_id, p.amount_minor, p.position::text
              FROM posting p
             WHERE p.tenant_id = :tenant AND p.account_id = :account
             ORDER BY p.position DESC, p.id DESC
             LIMIT :limit
            """;

    static final String OLDER_THAN = """
            SELECT p.id, p.entry_id, p.amount_minor, p.position::text
              FROM posting p
             WHERE p.tenant_id = :tenant AND p.account_id = :account
               AND (p.position, p.id) < (CAST(:position AS xid8), :id)
             ORDER BY p.position DESC, p.id DESC
             LIMIT :limit
            """;

    private final StatelessSession session;
    private final Installation installation;

    AccountHistory(StatelessSession session, Installation installation) {
        this.session = session;
        this.installation = installation;
    }

    /**
     * The newest {@code limit} Postings against the Account older than {@code after}, or the newest
     * of all when there is no cursor. Empty when this Tenant has no such Account.
     */
    @Transactional(readOnly = true)
    public Optional<Page> newestFirst(AccountId account, Optional<Cursor> after, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("a page holds at least one Posting, asked for " + limit);
        }
        TenantId tenant = TenantContext.required();
        var accountRow = session.get(AccountEntity.class, TenantScopedId.of(tenant, account.value()));
        if (accountRow == null) {
            return Optional.empty();
        }
        // One more than asked for: whether a next page exists, without a second query to find out.
        List<Object[]> rows = pageQuery(after)
                .setParameter("tenant", tenant.value())
                .setParameter("account", account.value())
                .setParameter("limit", limit + 1)
                .getResultList();

        List<RecordedPosting> postings = new ArrayList<>(Math.min(rows.size(), limit));
        for (Object[] postingRow : rows.subList(0, Math.min(rows.size(), limit))) {
            postings.add(toPosting(postingRow, accountRow.currency()));
        }
        Optional<Cursor> next = rows.size() > limit
                ? Optional.of(new Cursor(postings.getLast().position(), postings.getLast().id()))
                : Optional.empty();
        return Optional.of(new Page(account, postings, next));
    }

    private NativeQuery<Object[]> pageQuery(Optional<Cursor> after) {
        if (after.isEmpty()) {
            return session.createNativeQuery(NEWEST_FIRST, Object[].class);
        }
        Cursor cursor = after.get();
        return session.createNativeQuery(OLDER_THAN, Object[].class)
                // pgjdbc has no xid8 type; it travels as text, and it is unsigned.
                .setParameter("position", Long.toUnsignedString(cursor.position().xid8()))
                .setParameter("id", cursor.id().value());
    }

    private RecordedPosting toPosting(Object[] postingRow, Currency currency) {
        return new RecordedPosting(
                new PostingId((UUID) postingRow[0]),
                new EntryId((UUID) postingRow[1]),
                Money.ofMinor(((Number) postingRow[2]).longValue(), currency),
                installation.position(Long.parseUnsignedLong((String) postingRow[3])));
    }
}
