package io.nostro.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class PositionTest {

    @Test
    void theTokenIsSystemIdentifierColonZeroPaddedXid8() {
        var p = new Position(7_223_372_036_854_775_807L, 42L);
        assertThat(p.token()).isEqualTo("7223372036854775807:00000000000000000042");
    }

    @Test
    void aTokenRoundTrips() {
        var p = new Position(123L, 9_000_000_000L);
        assertThat(Position.parse(p.token())).contains(p);
    }

    @Test
    void malformedTokensParseToEmpty() {
        assertThat(Position.parse("")).isEmpty();
        assertThat(Position.parse(null)).isEmpty();
        assertThat(Position.parse("123")).isEmpty();
        assertThat(Position.parse("abc:00000000000000000042")).isEmpty();
        assertThat(Position.parse("123:42")).isEmpty();
        assertThat(Position.parse("123:0000000000000000004x")).isEmpty();
    }

    @Test
    void positionsFromOneInstallationCompareByXid() {
        var earlier = new Position(1L, 10L);
        var later = new Position(1L, 11L);
        assertThat(later.isAtLeast(earlier)).isTrue();
        assertThat(earlier.isAtLeast(later)).isFalse();
        assertThat(earlier.isAtLeast(earlier)).isTrue();
    }

    @Test
    void positionsFromDifferentInstallationsAreNotComparable() {
        var here = new Position(1L, 10L);
        var elsewhere = new Position(2L, 10L);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> here.isAtLeast(elsewhere))
                .withMessageContaining("installation");
    }
}
