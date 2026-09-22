package io.nostro.api.docs;

import io.nostro.api.problem.ProblemType;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The refusals a handler can answer with, beyond the ones every handler shares, for the OpenAPI
 * document (generated from the code, never hand-maintained). {@code 400 malformed} is
 * on every operation, and {@code 401} and {@code 403} on every one that {@code @Requires} a
 * Permission, without being declared here.
 *
 * <p>A declaration, not an enforcement: the code answers what it answers. {@code OpenApiIT} holds
 * the two together by requiring every {@link ProblemType} to appear in the document.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Refuses {

    ProblemType[] value();
}
