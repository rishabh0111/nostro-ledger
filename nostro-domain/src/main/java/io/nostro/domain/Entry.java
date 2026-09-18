package io.nostro.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The whole balanced fact: a set of Postings, recorded together, whose Amounts sum to zero within
 * each Currency they touch. Immutable once recorded; a mistake is corrected by a Reversing Entry.
 *
 * <p>An Entry cannot be constructed unbalanced. The rule lives in {@link #imbalance(List)} so the
 * recording path can report the refusal as a value; the constructor merely asserts it.
 *
 * @param id          the identity within its Tenant
 * @param postings    at least one Posting, balanced per Currency
 * @param reverses    the Entry this one undoes, if it is a Reversing Entry
 * @param description free text supplied by the caller, may be null
 */
public record Entry(EntryId id, List<Posting> postings, Optional<EntryId> reverses, String description) {

    public Entry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(reverses, "reverses");
        postings = List.copyOf(Objects.requireNonNull(postings, "postings"));
        if (postings.isEmpty()) {
            throw new IllegalArgumentException("an entry has at least one posting");
        }
        imbalance(postings).ifPresent(unbalanced -> {
            throw new IllegalArgumentException("an entry must balance: " + unbalanced.netByCurrency());
        });
    }

    /**
     * The balancing rule. Postings are netted within each Currency separately; Currencies never net
     * against each other (ADR-0002). Returns the refusal, naming only the Currencies that fail.
     */
    public static Optional<RecordOutcome.Unbalanced> imbalance(List<Posting> postings) {
        Map<Currency, Money> net = new LinkedHashMap<>();
        for (Posting posting : postings) {
            net.merge(posting.amount().currency(), posting.amount(), Money::plus);
        }
        net.values().removeIf(Money::isZero);
        return net.isEmpty() ? Optional.empty() : Optional.of(new RecordOutcome.Unbalanced(Map.copyOf(net)));
    }
}
