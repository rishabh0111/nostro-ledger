package io.nostro.persistence;

import io.nostro.domain.Position;
import java.util.Optional;

/**
 * The Postgres cluster this ledger lives in, identified by its {@code system_identifier}. Read by
 * the migration as the owner and held here so every Position issued carries the prefix that makes
 * a token from a rebuilt database meaningless rather than plausible (ADR-0007).
 */
public record Installation(long systemIdentifier) {

    public Position position(long xid8) {
        return new Position(systemIdentifier, xid8);
    }

    /**
     * A caller-presented token as a Position of this installation, or empty if it is not one:
     * malformed, or issued by a different cluster. The two are the same answer on purpose — a
     * token from elsewhere is not stale or fresh, it is not a Position here.
     */
    public Optional<Position> parse(String token) {
        return Position.parse(token).filter(position -> position.installation() == systemIdentifier);
    }
}
