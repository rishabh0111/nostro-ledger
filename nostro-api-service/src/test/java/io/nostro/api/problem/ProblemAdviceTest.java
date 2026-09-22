package io.nostro.api.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The advice on its own, against a throwaway controller and no application context (ADR-0012):
 * what it stamps, what it floors, and what it leaves alone.
 */
class ProblemAdviceTest {

    @RestController
    static class Throwing {
        @GetMapping("/typed/{id}")
        String typed(@PathVariable UUID id) {
            return id.toString();
        }

        @GetMapping("/refused")
        String refused() {
            throw ProblemType.UNKNOWN_CURRENCY.exception("no such currency");
        }

        @GetMapping("/broken")
        String broken() {
            throw new IllegalStateException("a secret the client must not see");
        }

        @GetMapping("/denied")
        String denied() {
            throw new AccessDeniedException("not yours");
        }
    }

    private final MockMvc http = MockMvcBuilders.standaloneSetup(new Throwing()).setControllerAdvice(new ProblemAdvice()).build();

    @Test
    @DisplayName("a 400 the framework makes on its own carries the malformed type, not about:blank")
    void aFrameworkBadRequestIsMalformed() throws Exception {
        http.perform(get("/typed/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(ProblemType.MALFORMED.uri().toString()))
                .andExpect(jsonPath("$.title").value(ProblemType.MALFORMED.title()));
    }

    @Test
    @DisplayName("a problem a handler throws is rendered as it was thrown")
    void aThrownProblemIsRenderedAsThrown() throws Exception {
        http.perform(get("/refused"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(ProblemType.UNKNOWN_CURRENCY.uri().toString()))
                .andExpect(jsonPath("$.detail").value("no such currency"));
    }

    @Test
    @DisplayName("anything else is a 500 Problem Details with no detail: what broke is for the log")
    void anUnexpectedFailureIsAFloor() throws Exception {
        var body = http.perform(get("/broken"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(500))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("secret");
    }

    @Test
    @DisplayName("the security layer's refusals pass through untouched, for the filter chain to answer")
    void securityRefusalsPassThrough() {
        assertThatThrownBy(() -> http.perform(get("/denied")))
                .hasCauseInstanceOf(AccessDeniedException.class);
    }
}
