package io.nostro.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.nostro.domain.Position;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InstallationTest {

    static final Installation HERE = new Installation(7_000_000_000L);

    @Test
    @DisplayName("a token this installation issued parses back to the Position it was")
    void parsesItsOwnTokens() {
        var issued = HERE.position(42);
        assertThat(HERE.parse(issued.token())).contains(new Position(7_000_000_000L, 42));
    }

    @Test
    @DisplayName("a token from another installation is not a Position here, and neither is a malformed one")
    void refusesForeignAndMalformedTokens() {
        var elsewhere = new Installation(7_000_000_001L).position(42).token();
        assertThat(HERE.parse(elsewhere)).isEmpty();
        assertThat(HERE.parse("7000000000:42")).isEmpty();
        assertThat(HERE.parse("")).isEmpty();
        assertThat(HERE.parse(null)).isEmpty();
    }
}
