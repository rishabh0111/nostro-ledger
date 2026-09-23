package io.nostro.grpc;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.util.ArrayList;
import java.util.UUID;

/**
 * Reads the Tenant from the call's metadata and binds it for the handler ({@link TenantMetadata#current}).
 * A call with no Tenant, an unreadable one, or more than one is closed {@code UNAUTHENTICATED}
 * before any handler runs: missing is never "no Tenant", which is the same fail-closed direction
 * as the database's policies.
 */
public final class TenantServerInterceptor implements ServerInterceptor {

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
        var values = new ArrayList<String>();
        Iterable<String> sent = headers.getAll(TenantMetadata.KEY);
        if (sent != null) {
            sent.forEach(values::add);
        }
        if (values.size() != 1) {
            return refuse(call, values.isEmpty() ? "no Tenant on the call" : "more than one Tenant on the call");
        }
        UUID tenant;
        try {
            tenant = UUID.fromString(values.getFirst());
        } catch (IllegalArgumentException notAUuid) {
            return refuse(call, "the Tenant on the call is not a Tenant id");
        }
        return Contexts.interceptCall(Context.current().withValue(TenantMetadata.TENANT, tenant), call, headers, next);
    }

    private static <Q, R> ServerCall.Listener<Q> refuse(ServerCall<Q, R> call, String why) {
        call.close(Status.UNAUTHENTICATED.withDescription(why), new Metadata());
        return new ServerCall.Listener<>() {
        };
    }
}
