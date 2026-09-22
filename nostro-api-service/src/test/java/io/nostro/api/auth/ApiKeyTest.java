package io.nostro.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ApiKeyTest {

    @Test
    @DisplayName("a generated key carries the prefix, 256 bits of randomness, and never repeats")
    void generatedKeysAreOpaqueAndDistinct() {
        var first = ApiKey.generate();
        var second = ApiKey.generate();

        assertThat(first.value()).startsWith("nk_").hasSize(3 + 43);
        assertThat(ApiKey.looksLike(first.value())).isTrue();
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("the stored form is the SHA-256 of the key, lower-case hex")
    void theHashIsSha256Hex() {
        var key = new ApiKey("nk_known");

        // echo -n nk_known | sha256sum
        assertThat(key.hash()).isEqualTo("672ea2680ec7c801cfa7445b658b0154c71aa1d11f4a7c5844e9f2514ebf2478");
    }

    @Test
    @DisplayName("a value without the prefix is not an API key")
    void thePrefixIsRequired() {
        assertThatThrownBy(() -> new ApiKey("eyJhbGciOi")).isInstanceOf(IllegalArgumentException.class);
        assertThat(ApiKey.looksLike("eyJhbGciOi")).isFalse();
    }

    @Test
    @DisplayName("toString never reveals the key")
    void toStringHidesTheKey() {
        assertThat(ApiKey.generate().toString()).isEqualTo("nk_...");
    }
}
