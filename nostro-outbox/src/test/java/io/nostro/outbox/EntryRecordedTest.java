package io.nostro.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nostro.domain.AccountId;
import io.nostro.domain.Currency;
import io.nostro.domain.Money;
import io.nostro.domain.Position;
import io.nostro.domain.Posting;
import io.nostro.domain.TenantId;
import io.nostro.outbox.EntryRecorded.PostingRecorded;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The message the three deployables agree on: what goes in comes out, and a message the ledger could not have written is refused on reading. */
class EntryRecordedTest {

    private final TenantId tenant = TenantId.random();
    private final AccountId cash = AccountId.random();
    private final AccountId bank = AccountId.random();

    @Test
    @DisplayName("what the ledger writes, the projection reads back in the domain's terms")
    void roundTrips() {
        var sent = message(List.of(posting(cash, "USD", -250), posting(bank, "USD", 250)), new Position(7, 42).token());

        var received = EntryRecorded.fromJson(sent.toJson());

        assertThat(received).isEqualTo(sent);
        // The read-back methods are not part of the wire format.
        assertThat(sent.toJson()).doesNotContain("\"tenant\"", "\"entry\"", "createdAt", "ledgerPostings");
        assertThat(received.tenant()).isEqualTo(tenant);
        assertThat(received.createdAt()).isEqualTo(new Position(7, 42));
        assertThat(received.ledgerPostings()).containsExactly(
                new Posting(cash, Money.ofMinor(-250, Currency.of("USD"))),
                new Posting(bank, Money.ofMinor(250, Currency.of("USD"))));
    }

    @Test
    @DisplayName("a Posting in a currency the ledger does not know, a zero Amount, no Postings or a malformed Position are refused on reading")
    void whatTheLedgerCouldNotHaveWrittenIsRefused() {
        var position = new Position(7, 42).token();

        assertThatThrownBy(() -> message(List.of(posting(cash, "XXX", 1), posting(bank, "XXX", -1)), position).ledgerPostings())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> message(List.of(posting(cash, "USD", 0)), position).ledgerPostings())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> message(List.of(), position).ledgerPostings())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> message(List.of(posting(cash, "USD", 1)), "not-a-position").createdAt())
                .isInstanceOf(IllegalArgumentException.class);
    }

    private EntryRecorded message(List<PostingRecorded> postings, String position) {
        return new EntryRecorded(tenant.toString(), UUID.randomUUID().toString(), position, null, "a test", postings);
    }

    private static PostingRecorded posting(AccountId account, String currency, long minor) {
        return new PostingRecorded(UUID.randomUUID().toString(), account.toString(), currency, minor);
    }
}
