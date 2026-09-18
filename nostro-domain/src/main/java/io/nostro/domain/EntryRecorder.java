package io.nostro.domain;

/**
 * Records an Entry for the current Tenant, in one database transaction, and answers with a value.
 *
 * <p>The Tenant is ambient: it comes from the credential the caller presented, never from the
 * command (ADR-0006). Implementations throw only for programmer error and broken infrastructure.
 */
public interface EntryRecorder {

    RecordOutcome record(LedgerCommand command);
}
