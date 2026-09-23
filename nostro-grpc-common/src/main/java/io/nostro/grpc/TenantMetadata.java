package io.nostro.grpc;

import io.grpc.Context;
import io.grpc.Metadata;
import java.util.Optional;
import java.util.UUID;

/**
 * How the Tenant crosses a gRPC boundary: one metadata key, written by {@link TenantClientInterceptor}
 * and read by {@link TenantServerInterceptor}, never a field on a request message
 * (docs/research/grpc-and-multi-module-layout.md section 6).
 *
 * <p>Not Micrometer baggage, which would travel the same way: that would make the isolation
 * boundary a property of the tracing configuration, and switching propagation formats would
 * silently drop it.
 *
 * <p>The server trusts the API service to have authenticated the caller; that is defensible only
 * because the projection's port is never published beyond the compose network. The moment it is,
 * this key is unauthenticated Tenant impersonation and the design must change to a signed token.
 */
public final class TenantMetadata {

    public static final Metadata.Key<String> KEY = Metadata.Key.of("x-nostro-tenant", Metadata.ASCII_STRING_MARSHALLER);

    static final Context.Key<UUID> TENANT = Context.key("nostro-tenant");

    private TenantMetadata() {
    }

    /** The Tenant the current call is acting for, as the server interceptor bound it; empty outside a call. */
    public static Optional<UUID> current() {
        return Optional.ofNullable(TENANT.get());
    }
}
