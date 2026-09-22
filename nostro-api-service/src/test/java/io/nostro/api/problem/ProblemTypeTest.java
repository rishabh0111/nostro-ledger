package io.nostro.api.problem;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** The catalog of stable {@code type} URIs: one namespace, no two alike, every status a refusal. */
class ProblemTypeTest {

    @Test
    @DisplayName("every type is a distinct URI under the one problems namespace")
    void everyTypeIsDistinctAndNamespaced() {
        var uris = Arrays.stream(ProblemType.values()).map(ProblemType::uri).toList();

        assertThat(uris).doesNotHaveDuplicates();
        assertThat(uris).allSatisfy(uri -> assertThat(uri.toString()).startsWith(ProblemType.NAMESPACE));
        assertThat(uris).noneMatch(uri -> uri.equals(URI.create("about:blank")));
    }

    @Test
    @DisplayName("every type carries a client-error status and a title")
    void everyTypeIsAClientError() {
        assertThat(ProblemType.values()).allSatisfy(type -> {
            assertThat(type.status().is4xxClientError()).as(type.name()).isTrue();
            assertThat(type.title()).as(type.name()).isNotBlank();
        });
    }

    @Test
    @DisplayName("a problem carries the type, its status and title, and the detail given")
    void aProblemCarriesTypeStatusTitleAndDetail() {
        var problem = ProblemType.UNBALANCED.problem("USD nets to 1.00");

        assertThat(problem.getType()).isEqualTo(ProblemType.UNBALANCED.uri());
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT.value());
        assertThat(problem.getTitle()).isEqualTo("Unbalanced");
        assertThat(problem.getDetail()).isEqualTo("USD nets to 1.00");
    }

    @Test
    @DisplayName("the exception form renders as the same problem")
    void theExceptionRendersTheSameProblem() {
        var exception = ProblemType.UNKNOWN_ACCOUNT.exception("no such account");

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exception.getBody().getType()).isEqualTo(ProblemType.UNKNOWN_ACCOUNT.uri());
        assertThat(exception.getBody().getDetail()).isEqualTo("no such account");
    }
}
