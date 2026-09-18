package io.nostro.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * ADR-0014: the outcome hierarchy is sealed so that an exhaustive switch over it is a compile-time
 * guarantee. This test pins the shape rather than any member, so adding a member is free and
 * un-sealing it is not.
 */
class RecordOutcomeTest {

    @Test
    void theOutcomeHierarchyIsSealedAllTheWayDown() {
        assertThat(RecordOutcome.class.isSealed()).isTrue();
        assertThat(RecordOutcome.Refused.class.isSealed()).isTrue();

        assertThat(Arrays.stream(RecordOutcome.Refused.class.getPermittedSubclasses()))
                .allSatisfy(c -> assertThat(c.isRecord()).as("%s is a record", c).isTrue());
    }

    @Test
    void everyFailureTheLedgerCanExpressIsAMember() {
        assertThat(Arrays.stream(RecordOutcome.Refused.class.getPermittedSubclasses()).map(Class::getSimpleName))
                .containsExactlyInAnyOrder(
                        "Unbalanced",
                        "UnknownAccount",
                        "CurrencyMismatch",
                        "InsufficientBalance",
                        "IdempotencyKeyReused",
                        "UnknownEntry",
                        "AlreadyReversed");
    }
}
