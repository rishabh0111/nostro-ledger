package io.nostro.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LedgerCommandTest {

    static final Currency USD = Currency.of("USD");
    static final AccountId A = new AccountId(UUID.fromString("00000000-0000-0000-0000-00000000000a"));
    static final AccountId B = new AccountId(UUID.fromString("00000000-0000-0000-0000-00000000000b"));
    static final IdempotencyKey KEY = new IdempotencyKey("order-1");

    @Test
    void theFingerprintIsStableForTheSameRequest() {
        var one = new RecordEntry(KEY, postings(-100, 100), "rent");
        var two = new RecordEntry(KEY, postings(-100, 100), "rent");

        assertThat(one.fingerprint()).isEqualTo(two.fingerprint()).hasSize(64);
    }

    @Test
    void theFingerprintChangesWhenTheRequestDoes() {
        var base = new RecordEntry(KEY, postings(-100, 100), "rent");

        assertThat(new RecordEntry(KEY, postings(-101, 101), "rent").fingerprint()).isNotEqualTo(base.fingerprint());
        assertThat(new RecordEntry(KEY, postings(-100, 100), "rent!").fingerprint()).isNotEqualTo(base.fingerprint());
        assertThat(new RecordEntry(KEY, postings(-100, 100), null).fingerprint()).isNotEqualTo(base.fingerprint());
        assertThat(new ReverseEntry(KEY, new EntryId(UUID.randomUUID()), "rent").fingerprint())
                .isNotEqualTo(base.fingerprint());
    }

    @Test
    void theFingerprintDoesNotDependOnTheKeyItself() {
        // The key is the identity; the fingerprint is what the key was presented with.
        var a = new RecordEntry(new IdempotencyKey("k1"), postings(-100, 100), "rent");
        var b = new RecordEntry(new IdempotencyKey("k2"), postings(-100, 100), "rent");
        assertThat(a.fingerprint()).isEqualTo(b.fingerprint());
    }

    @Test
    void postingOrderIsPartOfTheRequest() {
        var ab = new RecordEntry(KEY, List.of(
                new Posting(A, Money.ofMinor(-100, USD)), new Posting(B, Money.ofMinor(100, USD))), null);
        var ba = new RecordEntry(KEY, List.of(
                new Posting(B, Money.ofMinor(100, USD)), new Posting(A, Money.ofMinor(-100, USD))), null);
        assertThat(ab.fingerprint()).isNotEqualTo(ba.fingerprint());
    }

    @Test
    void aRecordEntryNeedsPostings() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RecordEntry(KEY, List.of(), null));
    }

    @Test
    void anIdempotencyKeyIsNonBlankAndBounded() {
        assertThatIllegalArgumentException().isThrownBy(() -> new IdempotencyKey(" "));
        assertThatIllegalArgumentException().isThrownBy(() -> new IdempotencyKey("x".repeat(129)));
        assertThat(new IdempotencyKey("x".repeat(128)).value()).hasSize(128);
    }

    private static List<Posting> postings(long a, long b) {
        return List.of(new Posting(A, Money.ofMinor(a, USD)), new Posting(B, Money.ofMinor(b, USD)));
    }
}
