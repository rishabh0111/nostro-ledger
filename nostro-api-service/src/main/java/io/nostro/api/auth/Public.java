package io.nostro.api.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A handler method that serves unauthenticated requests: the login endpoint, and so far nothing
 * else. Explicit, so that the absence of {@link Requires} is always a mistake and never a choice.
 * The security filter chain permits the same paths; the two declarations sit side by side in
 * {@link SecurityConfiguration}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Public {
}
