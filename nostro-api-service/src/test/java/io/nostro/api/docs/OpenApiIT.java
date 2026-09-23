package io.nostro.api.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nostro.api.LedgerIntegrationTest;
import io.nostro.api.auth.RequiredPermissions;
import io.nostro.api.problem.ProblemType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * The generated document against the code it was generated from (never hand-maintained): reachable without a
 * credential, every path under {@code /v1}, every handler the startup check accepted present, and
 * every {@link ProblemType} in it.
 */
class OpenApiIT extends LedgerIntegrationTest {

    @Autowired
    RequiredPermissions requiredPermissions;

    @Test
    @DisplayName("the document is public, and every path in it carries /v1")
    void theDocumentIsPublicAndVersioned() throws Exception {
        var document = document();

        assertThat(document.get("openapi").asString()).startsWith("3.");
        http.perform(get("/v3/api-docs.yaml")).andExpect(status().isOk());
        http.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
        http.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        assertThat(document.get("info").get("title").asString()).isEqualTo("Nostro");
        assertThat(document.get("paths").propertyNames()).isNotEmpty().allSatisfy(path -> assertThat(path).startsWith("/v1/"));
    }

    @Test
    @DisplayName("every handler the startup check accepted is an operation in the document")
    void everyHandlerIsAnOperation() throws Exception {
        var operationIds = new ArrayList<String>();
        document().get("paths").forEach(path -> path.forEach(operation -> operationIds.add(operation.get("operationId").asString())));

        var handlers = requiredPermissions.verifiedEndpoints().stream().map(name -> name.substring(name.indexOf('.') + 1)).toList();
        assertThat(handlers).isNotEmpty();
        assertThat(operationIds).containsAll(handlers);
    }

    @Test
    @DisplayName("every type URI in the catalog appears in the document: on the ProblemDetail schema, and as a documented response")
    void everyProblemTypeAppears() throws Exception {
        var document = document();
        var typeEnum = document.get("components").get("schemas").get("ProblemDetail").get("properties").get("type").get("enum");
        var enumerated = new ArrayList<String>();
        typeEnum.forEach(uri -> enumerated.add(uri.asString()));

        var responseDescriptions = new ArrayList<String>();
        document.get("paths").forEach(path -> path.forEach(operation ->
                operation.get("responses").forEach(response -> responseDescriptions.add(response.path("description").asString()))));

        for (ProblemType type : ProblemType.values()) {
            assertThat(enumerated).as("enumerated on ProblemDetail.type").contains(type.uri().toString());
            assertThat(responseDescriptions).as("a response of some operation names " + type)
                    .anySatisfy(description -> assertThat(description).contains(type.uri().toString()));
        }
        assertThat(enumerated).contains("about:blank");
    }

    @Test
    @DisplayName("an operation that requires a Permission documents 401 and 403, and 429 where a Tenant's budget is spent; the public one documents none and needs no credential")
    void securityIsDocumentedPerOperation() throws Exception {
        var paths = document().get("paths");
        var openAccount = paths.get("/v1/accounts").get("post");
        var login = paths.get("/v1/auth/login").get("post");

        assertThat(openAccount.get("responses").propertyNames()).contains("201", "400", "401", "403", "409", "429");
        assertThat(paths.get("/v1/control/tenants").get("post").get("responses").propertyNames())
                .as("the control plane has no Tenant, so no budget").contains("401", "403").doesNotContain("429");
        assertThat(openAccount.has("security")).as("inherits the document's bearer requirement").isFalse();
        assertThat(login.get("security")).as("explicitly none").isEmpty();
        assertThat(login.get("responses").propertyNames()).contains("200", "400", "401").doesNotContain("403", "429");
    }

    @Test
    @DisplayName("a refused Entry's responses are the problem media type with the shared schema")
    void refusalsAreProblemJson() throws Exception {
        var record = document().get("paths").get("/v1/entries").get("post").get("responses");

        List<String> statuses = new ArrayList<>();
        record.propertyNames().forEach(statuses::add);
        assertThat(statuses).containsExactlyInAnyOrder("201", "400", "401", "403", "404", "422", "429");
        var unprocessable = record.get("422");
        assertThat(unprocessable.get("description").asString())
                .contains(ProblemType.UNBALANCED.uri().toString())
                .contains(ProblemType.INSUFFICIENT_BALANCE.uri().toString());
        assertThat(unprocessable.get("content").get("application/problem+json").get("schema").get("$ref").asString())
                .isEqualTo("#/components/schemas/ProblemDetail");
    }

    private JsonNode document() throws Exception {
        var body = http.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }
}
