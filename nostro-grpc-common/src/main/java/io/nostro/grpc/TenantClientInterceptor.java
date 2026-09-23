package io.nostro.grpc;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Writes the calling thread's Tenant onto every outgoing call, so no code that makes a call has to
 * remember to. The Tenant comes from wherever the caller's validated credential put it — for the
 * API service, its {@code TenantContext} — never from anything the request carried.
 *
 * <p>Any Tenant already on the call is discarded first: exactly one goes out, and it is this one.
 * With no Tenant bound the call fails {@code UNAUTHENTICATED} before it leaves the process.
 */
public final class TenantClientInterceptor implements ClientInterceptor {

    private final Supplier<Optional<UUID>> tenant;

    public TenantClientInterceptor(Supplier<Optional<UUID>> tenant) {
        this.tenant = tenant;
    }

    @Override
    public <Q, R> ClientCall<Q, R> interceptCall(MethodDescriptor<Q, R> method, CallOptions options, Channel next) {
        UUID bound = tenant.get().orElseThrow(() ->
                Status.UNAUTHENTICATED.withDescription("no Tenant is bound to the calling thread").asRuntimeException());
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, options)) {
            @Override
            public void start(Listener<R> listener, Metadata headers) {
                headers.discardAll(TenantMetadata.KEY);
                headers.put(TenantMetadata.KEY, bound.toString());
                super.start(listener, headers);
            }
        };
    }
}
