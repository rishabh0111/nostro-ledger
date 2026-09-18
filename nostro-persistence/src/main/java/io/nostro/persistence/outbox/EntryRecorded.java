package io.nostro.persistence.outbox;

import io.nostro.domain.Entry;
import io.nostro.domain.Position;
import io.nostro.domain.TenantId;
import io.nostro.persistence.entity.PostingEntity;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/**
 * The outbox payload: one message per Entry carrying all its Postings, so a consumer can never
 * observe an Entry mid-way (docs/research/ordering-and-watermarks.md section 4). Nothing consumes
 * it until the relay exists; the shape is fixed now because the transaction that records an Entry must have
 * always written it.
 *
 * @param tenantId    the Tenant, which is also the partition key the relay will use
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

    public static EntryRecorded of(TenantId tenant, Entry entry, List<PostingEntity> postings, Position position) {
        return new EntryRecorded(
                tenant.toString(),
                entry.id().toString(),
                position.token(),
                entry.reverses().map(Object::toString).orElse(null),
                entry.description(),
                postings.stream()
                        .map(p -> new PostingRecorded(p.getId().id().toString(), p.accountId().toString(),
                                p.getAmount().currency(), p.getAmount().amountMinor()))
                        .toList());
    }

    public String toJson() {
        return JSON.writeValueAsString(this);
    }

    public static EntryRecorded fromJson(String json) {
        return JSON.readValue(json, EntryRecorded.class);
    }
}
