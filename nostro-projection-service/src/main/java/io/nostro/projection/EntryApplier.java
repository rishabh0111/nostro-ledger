package io.nostro.projection;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.nostro.domain.Entry;
import io.nostro.domain.Position;
import io.nostro.domain.Posting;
import io.nostro.domain.TenantId;
import io.nostro.outbox.EntryRecorded;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Applies one Entry to the projection, in one transaction, at most once.
 *
 * <p>The transaction writes the {@code applied_entry} row, every Posting's move, the Tenant's
 * watermark, and the consumer's next offset, and commits them together. The two guards against a
 * second application cover different failures (ADR-0010): the stored offset covers a crash between
 * applying and moving on, because the offset never moved; the {@code applied_entry} primary key
 * covers everything that arrives at a new offset — a relay's republish, a replay from zero — which
 * the offset cannot recognise.
 *
 * <p>The Entry is applied whole or not at all, so at every point a reader can observe, a Tenant's
 * Balances sum to zero in each Currency. That is the reason the topic is keyed by Tenant and each
 * message carries every Posting (docs/research/ordering-and-watermarks.md section 4).
 */
@Component
class EntryApplier {

    private static final Logger log = LoggerFactory.getLogger(EntryApplier.class);

    enum Outcome { APPLIED, ALREADY_APPLIED }

    /** Where a message came from, and so what the consumer's next offset becomes once it is applied. */
    record Source(String topic, int partition, long offset) {
    }

    static final String CLAIM = """
            INSERT INTO applied_entry (tenant_id, entry_id, position)
            VALUES (:tenant, :entry, CAST(:position AS xid8))
            ON CONFLICT (tenant_id, entry_id) DO NOTHING
            """;

    /** Moves one Account's Balance, refusing — by updating nothing — a Posting in another Currency than the Account's. */
    static final String MOVE = """
            INSERT INTO projected_balance AS b (tenant_id, account_id, currency, amount_minor)
            VALUES (:tenant, :account, :currency, :amount)
            ON CONFLICT (tenant_id, account_id)
            DO UPDATE SET amount_minor = b.amount_minor + excluded.amount_minor
                    WHERE b.currency = excluded.currency
            """;

    static final String WATERMARK = """
            SELECT ledger_installation, position::text AS position_token FROM tenant_watermark WHERE tenant_id = :tenant
            """;

    static final String ADVANCE_WATERMARK = """
            INSERT INTO tenant_watermark AS w (tenant_id, ledger_installation, position)
            VALUES (:tenant, :installation, CAST(:position AS xid8))
            ON CONFLICT (tenant_id) DO UPDATE SET position = greatest(w.position, excluded.position)
            """;

    static final String ADVANCE_OFFSET = """
            INSERT INTO consumer_position (topic, partition, next_offset)
            VALUES (:topic, :partition, :next)
            ON CONFLICT (topic, partition) DO UPDATE SET next_offset = excluded.next_offset
            """;

    private final TenantTransactions transactions;
    private final Watermarks watermarks;
    private final Counter applied;
    private final Counter duplicates;
    private final Counter reorders;

    EntryApplier(TenantTransactions transactions, Watermarks watermarks, MeterRegistry meters) {
        this.transactions = transactions;
        this.watermarks = watermarks;
        this.applied = Counter.builder("nostro.projection.entries")
                .description("Entries the projection has taken from Kafka, by what became of them")
                .tag("outcome", "applied").register(meters);
        this.duplicates = Counter.builder("nostro.projection.entries")
                .description("Entries the projection has taken from Kafka, by what became of them")
                .tag("outcome", "already-applied").register(meters);
        // Must stay at zero. The relay publishes a Tenant's Entries in Position order onto one
        // partition; an Entry arriving below the watermark means that assumption has broken, and
        // this is the only thing that would say so (docs/research/ordering-and-watermarks.md, "The one test").
        this.reorders = Counter.builder("nostro.projection.reorders")
                .description("Entries that arrived below their Tenant's watermark; anything but zero means the ordering assumption broke")
                .register(meters);
    }

    /**
     * Applies the message, or says it was applied before. Throws {@link Unappliable} for a message
     * the ledger could not have written; anything else thrown is the database's, and the caller
     * decides whether it was transient.
     */
    Outcome apply(String key, String value, Source source) {
        var message = parse(key, value);
        TenantId tenant = message.tenant();
        Position position = message.createdAt();
        List<Posting> postings = message.ledgerPostings();
        Entry.imbalance(postings).ifPresent(unbalanced -> {
            throw new Unappliable("Entry " + message.entryId() + " does not balance: " + unbalanced);
        });

        Outcome outcome = transactions.writingAs(tenant, jdbc -> {
            if (claim(jdbc, message, position) == 0) {
                advanceOffset(jdbc, source);
                return Outcome.ALREADY_APPLIED;
            }
            for (Posting posting : postings) {
                move(jdbc, tenant, posting, message);
            }
            advanceWatermark(jdbc, tenant, position, message);
            advanceOffset(jdbc, source);
            return Outcome.APPLIED;
        });

        if (outcome == Outcome.APPLIED) {
            applied.increment();
            watermarks.advanced(tenant, position);
        } else {
            duplicates.increment();
        }
        return outcome;
    }

    private static EntryRecorded parse(String key, String value) {
        try {
            var message = EntryRecorded.fromJson(value);
            if (!message.tenant().toString().equals(key)) {
                throw new Unappliable("message keyed " + key + " carries an Entry of Tenant " + message.tenantId()
                        + ": the key is what orders a Tenant's Entries, and this one is on the wrong partition");
            }
            message.entry();
            return message;
        } catch (Unappliable unappliable) {
            throw unappliable;
        } catch (RuntimeException unreadable) {
            throw new Unappliable("not an Entry the ledger could have written: " + unreadable.getMessage(), unreadable);
        }
    }

    private static int claim(JdbcClient jdbc, EntryRecorded message, Position position) {
        return jdbc.sql(CLAIM)
                .param("tenant", message.tenant().value())
                .param("entry", message.entry().value())
                .param("position", Long.toUnsignedString(position.xid8()))
                .update();
    }

    private static void move(JdbcClient jdbc, TenantId tenant, Posting posting, EntryRecorded message) {
        int moved = jdbc.sql(MOVE)
                .param("tenant", tenant.value())
                .param("account", posting.account().value())
                .param("currency", posting.amount().currency().code())
                .param("amount", posting.amount().minor())
                .update();
        if (moved == 0) {
            throw new Unappliable("Entry " + message.entryId() + " posts " + posting.amount().currency().code()
                    + " to Account " + posting.account() + ", which the projection holds in another Currency");
        }
    }

    private void advanceWatermark(JdbcClient jdbc, TenantId tenant, Position position, EntryRecorded message) {
        Optional<Position> current = jdbc.sql(WATERMARK)
                .param("tenant", tenant.value())
                .query((row, n) -> new Position(row.getLong(1), Long.parseUnsignedLong(row.getString(2))))
                .optional();
        if (current.isPresent()) {
            if (current.get().installation() != position.installation()) {
                throw new Unappliable("Entry " + message.entryId() + " carries a Position of installation "
                        + position.installation() + ", and this projection holds Tenant " + tenant + " at installation "
                        + current.get().installation() + ": a rebuilt ledger needs a rebuilt projection");
            }
            if (!position.isAtLeast(current.get())) {
                reorders.increment();
                log.error("Entry {} of Tenant {} arrived at {}, below the watermark {}: the ordering assumption has broken",
                        message.entryId(), tenant, position, current.get());
            }
        }
        jdbc.sql(ADVANCE_WATERMARK)
                .param("tenant", tenant.value())
                .param("installation", position.installation())
                .param("position", Long.toUnsignedString(position.xid8()))
                .update();
    }

    private static void advanceOffset(JdbcClient jdbc, Source source) {
        jdbc.sql(ADVANCE_OFFSET)
                .param("topic", source.topic())
                .param("partition", source.partition())
                .param("next", source.offset() + 1)
                .update();
    }
}
