package io.nostro.domain;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A comparable marker of how much of a Tenant's history a Balance reflects (ADR-0007).
 *
 * <p>Opaque to callers, who compare Positions only by presenting them. The token is
 * {@code <system_identifier>:<xid8>} with the xid8 zero-padded to twenty digits: the transaction id
 * assigned inside the Entry's own transaction, namespaced by the Postgres installation that
 * assigned it, so that a token from a restored or rebuilt database fails loudly instead of
 * comparing as a plausible number (docs/research/ordering-and-watermarks.md).
 *
 * @param installation the database cluster's {@code system_identifier}
 * @param xid8         the 64-bit transaction id, which never wraps within one installation
 */
public record Position(long installation, long xid8) {

    private static final Pattern TOKEN = Pattern.compile("(\\d{1,20}):(\\d{20})");

    public String token() {
        return Long.toUnsignedString(installation) + ":" + String.format("%020d", xid8);
    }

    public static Optional<Position> parse(String token) {
        if (token == null) {
            return Optional.empty();
        }
        var m = TOKEN.matcher(token);
        if (!m.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Position(Long.parseUnsignedLong(m.group(1)), Long.parseUnsignedLong(m.group(2))));
        } catch (NumberFormatException overflow) {
            return Optional.empty();
        }
    }

    /**
     * Whether this Position reflects at least as much history as {@code minimum}. Only meaningful
     * within one installation; a Position from another is not stale, it is meaningless.
     */
    public boolean isAtLeast(Position minimum) {
        if (minimum.installation != installation) {
            throw new IllegalArgumentException(
                    "position " + minimum.token() + " was issued by a different installation than " + token());
        }
        return Long.compareUnsigned(xid8, minimum.xid8) >= 0;
    }

    @Override
    public String toString() {
        return token();
    }
}
