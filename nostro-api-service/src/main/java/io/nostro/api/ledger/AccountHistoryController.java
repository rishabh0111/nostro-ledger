package io.nostro.api.ledger;

import io.nostro.api.auth.Permission;
import io.nostro.api.auth.Requires;
import io.nostro.api.docs.Refuses;
import io.nostro.api.problem.ProblemType;
import io.nostro.domain.AccountId;
import io.nostro.persistence.Installation;
import io.nostro.persistence.ledger.AccountHistory;
import io.nostro.persistence.ledger.AccountHistory.Cursor;
import io.nostro.persistence.ledger.AccountHistory.Page;
import io.nostro.persistence.ledger.AccountHistory.RecordedPosting;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * An Account's history over HTTP: Postings newest first, paged by an opaque cursor
 * ({@link Cursor}); callers hand it back, never read it. A cursor that is not one of ours, or a
 * page size outside the bounds, is {@code 400 malformed} (ADR-0014).
 */
@RestController
class AccountHistoryController {

    static final int DEFAULT_PAGE = 50;
    static final int LARGEST_PAGE = 200;

    private final AccountHistory history;
    private final Installation installation;

    AccountHistoryController(AccountHistory history, Installation installation) {
        this.history = history;
        this.installation = installation;
    }

    @Requires(Permission.LEDGER_READ)
    @Refuses(ProblemType.UNKNOWN_ACCOUNT)
    @GetMapping("/accounts/{id}/postings")
    ResponseEntity<HistoryResponse> newestFirst(
            @PathVariable UUID id,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE) int limit) {
        if (limit < 1 || limit > LARGEST_PAGE) {
            throw ProblemType.MALFORMED.exception("limit must be between 1 and " + LARGEST_PAGE);
        }
        Optional<Cursor> after = cursor == null
                ? Optional.empty()
                : Optional.of(Cursor.parse(cursor, installation).orElseThrow(() ->
                        ProblemType.MALFORMED.exception("cursor is not a cursor of this ledger")));
        return history.newestFirst(new AccountId(id), after, limit)
                .map(page -> ResponseEntity.ok(HistoryResponse.of(page)))
                .orElseThrow(() -> LedgerRefusals.absentAccount(new AccountId(id)));
    }

    record HistoryResponse(UUID account, List<PostingResponse> postings, @Nullable String nextCursor) {
        static HistoryResponse of(Page page) {
            return new HistoryResponse(
                    page.account().value(),
                    page.postings().stream().map(PostingResponse::of).toList(),
                    page.next().map(Cursor::token).orElse(null));
        }
    }

    record PostingResponse(UUID id, UUID entry, MoneyJson amount, String position) {
        static PostingResponse of(RecordedPosting posting) {
            return new PostingResponse(posting.id().value(), posting.entry().value(), MoneyJson.of(posting.amount()), posting.position().token());
        }
    }
}
