package io.nostro.api.docs;

import io.nostro.api.ApiVersion;
import io.nostro.api.auth.LoginController;
import io.nostro.api.auth.Public;
import io.nostro.api.auth.Requires;
import io.nostro.api.problem.ProblemType;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;

/**
 * The OpenAPI document, generated from the code and never hand-maintained. springdoc
 * reads the handlers; what it cannot read off a signature is added here from the same
 * declarations the code runs on: {@code @Requires} says which operations need a credential,
 * {@code @Public} which do not, and {@link Refuses} which Problem Details an operation answers
 * beyond the ones every operation shares. Every {@link ProblemType} is enumerated on the
 * {@code ProblemDetail} schema's {@code type}, so the catalog is in the document whether or not an
 * operation declares it, and {@code OpenApiIT} holds the two together.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    static final String BEARER = "bearer";
    static final String PROBLEM_DETAIL = "ProblemDetail";
    static final String PROBLEM_JSON = "application/problem+json";

    @Bean
    OpenAPI nostroApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Nostro")
                        .version("v1")
                        .description("""
                                A multitenant double-entry ledger. Tenants record movements of money; the system's job \
                                is to make it structurally impossible to record one that does not balance, to lose one, \
                                to apply one twice, or to let one tenant's money touch another's.

                                The Tenant is derived from the credential and never named in a request. Every refusal \
                                is an RFC 9457 `application/problem+json` body whose `type` is one of a closed set, \
                                enumerated on the `ProblemDetail` schema."""))
                .components(new Components()
                        .addSecuritySchemes(BEARER, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme(BEARER)
                                .description("An API key (`nk_...`) or a staff token from `POST " + ApiVersion.V1 + LoginController.PATH
                                        + "`; the control plane's bootstrap key (`nc_...`) on `" + ApiVersion.V1 + "/control/...`."))
                        .addSchemas(PROBLEM_DETAIL, problemDetailSchema()))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }

    /** Per operation: no credential where {@code @Public}, and the refusals as responses. */
    @Bean
    OperationCustomizer refusalsAndSecurity() {
        return (Operation operation, HandlerMethod handler) -> {
            if (handler.hasMethodAnnotation(Public.class)) {
                operation.setSecurity(List.of());
            }
            var refusals = EnumSet.of(ProblemType.MALFORMED);
            if (handler.hasMethodAnnotation(Requires.class)) {
                refusals.add(ProblemType.UNAUTHENTICATED);
                refusals.add(ProblemType.FORBIDDEN);
            }
            Refuses declared = handler.getMethodAnnotation(Refuses.class);
            if (declared != null) {
                refusals.addAll(Arrays.asList(declared.value()));
            }
            Map<Integer, List<ProblemType>> byStatus = new TreeMap<>();
            for (ProblemType type : refusals) {
                byStatus.computeIfAbsent(type.status().value(), status -> new ArrayList<>()).add(type);
            }
            byStatus.forEach((status, types) -> operation.getResponses().addApiResponse(String.valueOf(status), problemResponse(types)));
            return operation;
        };
    }

    /** Sorts the paths, so the document reads the same however the handlers were discovered. */
    @Bean
    OpenApiCustomizer sortedPaths() {
        return openApi -> {
            var sorted = new Paths();
            new TreeMap<>(openApi.getPaths()).forEach(sorted::addPathItem);
            openApi.setPaths(sorted);
        };
    }

    /** One response per status; several types with one status list them all, first by their titles. */
    static ApiResponse problemResponse(List<ProblemType> types) {
        String description = types.stream()
                .map(type -> type.title() + " (`" + type.uri() + "`)")
                .collect(Collectors.joining(", "));
        return new ApiResponse()
                .description(description)
                .content(new Content().addMediaType(PROBLEM_JSON,
                        new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM_DETAIL))));
    }

    /** RFC 9457's shape, with {@code type} closed over the catalog plus the RFC's own default. */
    static Schema<?> problemDetailSchema() {
        var types = new StringSchema();
        types.format("uri");
        types.description("Which refusal this is: stable, and the field a client switches on. "
                + "`about:blank` means the status is the whole story.");
        types.addEnumItem("about:blank");
        for (ProblemType type : ProblemType.values()) {
            types.addEnumItem(type.uri().toString());
        }
        return new ObjectSchema()
                .description("An RFC 9457 Problem Details body. Every refusal this API makes is one.")
                .addProperty("type", types)
                .addProperty("title", new StringSchema().description("A short name for the type; the same every time."))
                .addProperty("status", new IntegerSchema().description("The HTTP status, repeated."))
                .addProperty("detail", new StringSchema().description("A sentence for a person about this occurrence; never a field a client switches on."))
                .addProperty("instance", new StringSchema().format("uri").description("The request path."))
                .required(List.of("type", "title", "status"));
    }
}
