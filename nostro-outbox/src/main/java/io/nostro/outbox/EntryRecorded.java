package io.nostro.outbox;

import io.nostro.domain.Entry;
import io.nostro.domain.Position;
import io.nostro.domain.TenantId;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/**
 * The outbox payload, and the Kafka message the relay publishes it as: one message per Entry
 * carrying all its Postings, so a consumer can never observe an Entry mid-way
 * (docs/research/ordering-and-watermarks.md section 4).
 *
 * <p>Written into the outbox in the Entry's own transaction, published by the relay byte for byte,
 * applied by the projection. The relay never parses it; the two ends agree through this record.
 *
 * @param tenantId    the Tenant, which is also the partition key ({@link EntryTopic#key})
 * @param entryId     the Entry
 * @param position    the Position the Entry created, as its token
 * @param reverses    the Entry this one reverses, or null
 * @param description the caller's free text, or null
 * @param postings    every Posting of the Entry, each with its own id
 */
public record EntryRecorded(
        String tenantId,
        String entryId,
        String position,
        String reverses,
        String description,
        List<PostingRecorded> postings) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record PostingRecorded(String postingId, String accountId, String currency, long amountMinor) {
    }

    public static EntryRecorded of(TenantId tenant, Entry entry, List<PostingRecorded> postings, Position position) {
        return new EntryRecorded(
                tenant.toString(),
                entry.id().toString(),
                position.token(),
                entry.reverses().map(Object::toString).orElse(null),
                entry.description(),
                List.copyOf(postings));
    }

    public String toJson() {
        return JSON.writeValueAsString(this);
    }

    public static EntryRecorded fromJson(String json) {
        return JSON.readValue(json, EntryRecorded.class);
    }
}
