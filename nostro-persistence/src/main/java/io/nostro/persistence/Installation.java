package io.nostro.persistence;

import io.nostro.domain.Position;

/**
 * The Postgres cluster this ledger lives in, identified by its {@code system_identifier}. Read by
 * the migration as the owner and held here so every Position issued carries the prefix that makes
 * a token from a rebuilt database meaningless rather than plausible (ADR-0007).
 */
public record Installation(long systemIdentifier) {

    public Position position(long xid8) {
        return new Position(systemIdentifier, xid8);
    }
}
