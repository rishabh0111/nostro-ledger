package io.nostro.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

/**
 * The startup half of {@link RequiredPermissions}: an endpoint added without a permission
 * declaration fails the build, because the application refuses to start (ADR-0006).
 */
class RequiredPermissionsTest {

    @Test
    @DisplayName("an endpoint that declares neither @Requires nor @Public stops the application from starting")
    void anUndeclaredEndpointFailsStartup() {
        var failure = catchThrowable(() -> RequiredPermissions.verify(List.of(
                handler(new Declared(), "read"), handler(new Undeclared(), "forgotten"))));

        assertThat(failure).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Undeclared.forgotten")
                .hasMessageContaining("@Requires or @Public");
    }

    @Test
    @DisplayName("an endpoint that requires no Permission at all has not declared either: @Requires({}) fails startup")
    void anEmptyDeclarationFailsStartup() {
        var failure = catchThrowable(() -> RequiredPermissions.verify(List.of(handler(new Undeclared(), "empty"))));

        assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessageContaining("Undeclared.empty");
    }

    @Test
    @DisplayName("endpoints that declare their Permissions, or declare themselves public, start and are reported")
    void declaredEndpointsStart() {
        var accepted = RequiredPermissions.verify(List.of(handler(new Declared(), "read"), handler(new Declared(), "login")));

        assertThat(accepted).containsExactly("Declared.read", "Declared.login");
    }

    @Test
    @DisplayName("handlers outside io.nostro, such as Spring's /error, are not this application's endpoints")
    void foreignHandlersAreNotChecked() throws NoSuchMethodException {
        var foreign = new HandlerMethod(new StringBuilder(), StringBuilder.class.getMethod("toString"));

        assertThatCode(() -> RequiredPermissions.verify(List.of(foreign))).doesNotThrowAnyException();
    }

    private static HandlerMethod handler(Object bean, String methodName) {
        return new HandlerMethod(bean, method(bean.getClass(), methodName));
    }

    private static Method method(Class<?> type, String name) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        throw new IllegalArgumentException(type + " has no method " + name);
    }

    static class Declared {
        @Requires(Permission.LEDGER_READ)
        public void read() {
        }

        @Public
        public void login() {
        }
    }

    static class Undeclared {
        public void forgotten() {
        }

        @Requires({})
        public void empty() {
        }
    }
}
