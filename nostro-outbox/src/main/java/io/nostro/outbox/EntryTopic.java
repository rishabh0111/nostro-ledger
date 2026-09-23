package io.nostro.outbox;

import io.nostro.domain.TenantId;

/**
 * The one topic Entries travel on, and the two facts about it that are schema rather than
 * configuration (docs/research/ordering-and-watermarks.md section 4).
 *
 * <p><b>Keyed by Tenant.</b> Kafka orders per partition, and a key orders only because it maps to
 * one; keying by Tenant puts every Entry of a Tenant on one partition, in the order the relay
 * published them. Not by Account: a sum commutes, so per-Account order buys nothing, and an Entry
 * split across Account-keyed partitions would let the projection be observed with Postings that do
 * not sum to zero.
 *
 * <p><b>A fixed partition count.</b> The default partitioner maps a key by {@code hash % partitions},
 * so adding partitions moves Tenants to partitions that hold none of their history, and nothing
 * orders the old against the new. Changing this number is a correctness event: a new topic and a
 * deliberate cut-over, never {@code --alter --partitions}. The relay refuses to start against a
 * topic whose count differs.
 */
public final class EntryTopic {

    public static final String NAME = "nostro.ledger.entries";

    /** Twelve: enough to show parallelism across Tenants, few enough to reason about. */
    public static final int PARTITIONS = 12;

    private EntryTopic() {
    }

    /** The message key: the Tenant, so that all of a Tenant's Entries share one partition. */
    public static String key(TenantId tenant) {
        return tenant.toString();
    }
}
