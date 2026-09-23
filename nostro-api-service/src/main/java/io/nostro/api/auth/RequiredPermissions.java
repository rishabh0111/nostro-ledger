package io.nostro.api.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Authorization that fails closed at startup (ADR-0006).
 *
 * <p>At startup, every handler method this application defines must carry {@link Requires} or
 * {@link Public}; one that carries neither stops the application from booting, so an endpoint
 * cannot be reachable without having said who may reach it. At request time the same rule is
 * enforced against the authenticated {@link Caller}: a handler asking for Permissions the Caller
 * does not hold is refused, and a handler that somehow asks for nothing is refused too.
 *
 * <p>The rule is scoped to handlers in {@code io.nostro}. Spring's own {@code /error} handler is
 * not an endpoint of this API and declares nothing.
 */
@Component
public class RequiredPermissions implements SmartInitializingSingleton, HandlerInterceptor {

    static final String OUR_PACKAGE = "io.nostro";

    // Resolved at check time, not construction: this bean is also the interceptor the MVC
    // configuration registers, and the handler mapping is built from that configuration.
    private final ObjectProvider<RequestMappingHandlerMapping> handlerMapping;

    private List<String> verified = List.of();

    // By name: actuator adds a second RequestMappingHandlerMapping, for its own controller endpoints,
    // which are not this application's and are secured by the filter chain.
    RequiredPermissions(@Qualifier("requestMappingHandlerMapping") ObjectProvider<RequestMappingHandlerMapping> handlerMapping) {
        this.handlerMapping = handlerMapping;
    }

    @Override
    public void afterSingletonsInstantiated() {
        verified = verify(handlerMapping.getObject().getHandlerMethods().values());
    }

    /** The endpoints the startup check saw and accepted, as {@code Type.method}; empty until it has run. */
    public List<String> verifiedEndpoints() {
        return verified;
    }

    /**
     * Refuses to proceed if any of this application's handler methods has not declared its
     * Permissions, or has declared none; returns the ones it accepted.
     */
    static List<String> verify(Collection<HandlerMethod> handlers) {
        List<String> accepted = new ArrayList<>();
        List<String> undeclared = new ArrayList<>();
        for (HandlerMethod handler : handlers) {
            if (!isOurs(handler)) {
                continue;
            }
            String name = handler.getBeanType().getSimpleName() + "." + handler.getMethod().getName();
            if (declares(handler)) {
                accepted.add(name);
            } else {
                undeclared.add(name);
            }
        }
        if (!undeclared.isEmpty()) {
            throw new IllegalStateException(
                    "endpoints reachable without a permission declaration; annotate each with @Requires or @Public: "
                            + String.join(", ", undeclared));
        }
        return List.copyOf(accepted);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method) || !isOurs(method) || method.hasMethodAnnotation(Public.class)) {
            return true;
        }
        Requires requires = method.getMethodAnnotation(Requires.class);
        if (requires == null || requires.value().length == 0) {
            // Unreachable once the startup check has run; refused anyway, because reachable by
            // accident is the failure mode this class exists to remove.
            throw new AccessDeniedException("endpoint declares no permission");
        }
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof CallerAuthentication authenticated)) {
            throw new AuthenticationCredentialsNotFoundException("no credential");
        }
        for (Permission permission : requires.value()) {
            if (!authenticated.caller().holds(permission)) {
                throw new AccessDeniedException("credential does not hold " + permission);
            }
        }
        return true;
    }

    static boolean isOurs(HandlerMethod handler) {
        return handler.getBeanType().getPackageName().startsWith(OUR_PACKAGE);
    }

    /** {@code @Requires({})} is not a declaration: it would let any credential through. */
    private static boolean declares(HandlerMethod handler) {
        Requires requires = handler.getMethodAnnotation(Requires.class);
        return (requires != null && requires.value().length > 0) || handler.hasMethodAnnotation(Public.class);
    }
}
