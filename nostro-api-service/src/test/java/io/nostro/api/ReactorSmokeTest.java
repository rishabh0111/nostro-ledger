package io.nostro.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Exists so that a clean checkout has one test to run before the schema arrives. */
class ReactorSmokeTest {

    @Test
    void theApplicationClassIsOnTheClasspath() {
        assertThat(NostroApiApplication.class.getPackageName()).isEqualTo("io.nostro.api");
    }
}
