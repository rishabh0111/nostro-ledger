package io.nostro.api.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The Permissions a handler method requires, all of them. Every endpoint carries this or
 * {@link Public}; {@link RequiredPermissions} refuses to let the application start otherwise, so an
 * endpoint cannot be reachable without having said who may reach it (ADR-0006).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Requires {
    Permission[] value();
}
