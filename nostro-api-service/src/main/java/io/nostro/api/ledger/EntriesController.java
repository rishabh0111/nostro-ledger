package io.nostro.api.ledger;

import io.nostro.api.auth.Permission;
import io.nostro.api.auth.Requires;
import io.nostro.domain.AccountId;
import io.nostro.domain.EntryId;
import io.nostro.domain.EntryRecorder;
import io.nostro.domain.IdempotencyKey;
import io.nostro.domain.LedgerCommand;
import io.nostro.domain.Posting;
import io.nostro.domain.RecordEntry;
import io.nostro.domain.RecordOutcome;
import io.nostro.domain.RecordOutcome.Recorded;
import io.nostro.domain.RecordOutcome.Refused;
import io.nostro.domain.ReverseEntry;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Recording an Entry over HTTP. The Tenant is the credential's (ADR-0006); the Idempotency Key is
 * the caller's, in the body, and the same key with the same request answers {@code 201} again with
 * what the first attempt answered (ADR-0005). A refusal is a value from the domain, mapped to
 * Problem Details by {@link LedgerRefusals} and nowhere else (ADR-0014).
 *
 * <p>A Reversing Entry is an ordinary Entry recorded against the one it reverses, so it has the
 * same answer shape and the same refusals, plus the two of its own.
 */
@RestController
class EntriesController {

    private final EntryRecorder recorder;

    EntriesController(EntryRecorder recorder) {
        this.recorder = recorder;
    }

    @Requires(Permission.LEDGER_WRITE)
    @PostMapping("/entries")
    ResponseEntity<EntryResponse> record(@RequestBody RecordEntryRequest request) {
        var postings = Requests.required(request.postings(), "postings").stream().map(EntriesController::posting).toList();
        var command = Requests.domain(() -> new RecordEntry(idempotencyKey(request.idempotencyKey()), postings, request.description()));
        return answer(recorder.record(command));
    }

    @Requires(Permission.LEDGER_WRITE)
    @PostMapping("/entries/{id}/reversal")
    ResponseEntity<EntryResponse> reverse(@PathVariable UUID id, @RequestBody ReverseEntryRequest request) {
        var command = new ReverseEntry(idempotencyKey(request.idempotencyKey()), new EntryId(id), request.description());
        return answer(recorder.record(command));
    }

    /** Both handlers end here: the outcome is either the Entry or one of the refusals, and nothing else exists. */
    private static ResponseEntity<EntryResponse> answer(RecordOutcome outcome) {
        return switch (outcome) {
            case Recorded recorded -> ResponseEntity.status(HttpStatus.CREATED).body(EntryResponse.of(recorded));
            case Refused refused -> throw LedgerRefusals.of(refused);
        };
    }

    private static IdempotencyKey idempotencyKey(@Nullable String value) {
        return Requests.domain(() -> new IdempotencyKey(Requests.required(value, "idempotencyKey")));
    }

    private static Posting posting(@Nullable PostingRequest posting) {
        var request = Requests.required(posting, "postings[]");
        var account = new AccountId(Requests.required(request.account(), "postings[].account"));
        var amount = Requests.money(request.amount(), "postings[].amount");
        return Requests.domain(() -> new Posting(account, amount));
    }

    record RecordEntryRequest(@Nullable String idempotencyKey, @Nullable List<PostingRequest> postings, @Nullable String description) {
    }

    record PostingRequest(@Nullable UUID account, @Nullable MoneyJson amount) {
    }

    record ReverseEntryRequest(@Nullable String idempotencyKey, @Nullable String description) {
    }

    record EntryResponse(UUID entry, String position) {
        static EntryResponse of(Recorded recorded) {
            return new EntryResponse(recorded.entry().value(), recorded.position().token());
        }
    }
}
