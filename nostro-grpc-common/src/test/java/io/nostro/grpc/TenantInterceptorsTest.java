package io.nostro.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.CallOptions;
import io.grpc.ClientInterceptors;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.InsecureServerCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.ServerCalls;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Tenant crosses the boundary in metadata, and the server refuses a call that carries none.
 * Over a real socket and the real transport: an in-process channel skips metadata serialization by
 * default, and a test of metadata that skips it proves nothing (ADR-0012).
 */
class TenantInterceptorsTest {

    /** A service that answers with the Tenant the server interceptor bound, and counts the calls that reached it. */
    private static final MethodDescriptor<String, String> WHO_AM_I = MethodDescriptor.<String, String>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("test.Tenancy/WhoAmI")
            .setRequestMarshaller(new Text())
            .setResponseMarshaller(new Text())
            .build();

    private final AtomicInteger reached = new AtomicInteger();
    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void serve() throws IOException {
        var service = ServerServiceDefinition.builder("test.Tenancy")
                .addMethod(WHO_AM_I, ServerCalls.asyncUnaryCall((request, response) -> {
                    reached.incrementAndGet();
                    response.onNext(TenantMetadata.current().map(UUID::toString).orElse("nobody"));
                    response.onCompleted();
                }))
                .build();
        server = Grpc.newServerBuilderForPort(0, InsecureServerCredentials.create())
                .addService(ServerInterceptors.intercept(service, new TenantServerInterceptor()))
                .build()
                .start();
        channel = Grpc.newChannelBuilderForAddress("localhost", server.getPort(), InsecureChannelCredentials.create()).build();
    }

    @AfterEach
    void shutDown() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("a call carrying no Tenant is UNAUTHENTICATED, and never reaches the service")
    void noTenantIsUnauthenticated() {
        assertThatThrownBy(() -> ClientCalls.blockingUnaryCall(channel, WHO_AM_I, CallOptions.DEFAULT, "?"))
                .isInstanceOfSatisfying(StatusRuntimeException.class, refused ->
                        assertThat(refused.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
        assertThat(reached).hasValue(0);
    }

    @Test
    @DisplayName("a Tenant that is not a UUID is UNAUTHENTICATED too: never 'no Tenant', never a guess")
    void anUnreadableTenantIsUnauthenticated() {
        var headers = new Metadata();
        headers.put(TenantMetadata.KEY, "tenant-a");

        assertThatThrownBy(() -> ClientCalls.blockingUnaryCall(
                ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(headers)), WHO_AM_I, CallOptions.DEFAULT, "?"))
                .isInstanceOfSatisfying(StatusRuntimeException.class, refused ->
                        assertThat(refused.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
        assertThat(reached).hasValue(0);
    }

    @Test
    @DisplayName("the client interceptor writes the caller's Tenant, and the service sees exactly that Tenant")
    void theTenantCrossesTheWire() {
        var tenant = UUID.randomUUID();
        var withTenant = ClientInterceptors.intercept(channel, new TenantClientInterceptor(() -> Optional.of(tenant)));

        assertThat(ClientCalls.blockingUnaryCall(withTenant, WHO_AM_I, CallOptions.DEFAULT, "?")).isEqualTo(tenant.toString());
    }

    @Test
    @DisplayName("with no Tenant bound on the calling side, the call fails closed before it leaves")
    void noTenantBoundFailsClosedAtTheClient() {
        var withoutTenant = ClientInterceptors.intercept(channel, new TenantClientInterceptor(Optional::empty));

        assertThatThrownBy(() -> ClientCalls.blockingUnaryCall(withoutTenant, WHO_AM_I, CallOptions.DEFAULT, "?"))
                .isInstanceOfSatisfying(StatusRuntimeException.class, refused ->
                        assertThat(refused.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
        assertThat(reached).hasValue(0);
    }

    @Test
    @DisplayName("a Tenant already on the call is replaced, never sent beside it: the client's bound Tenant is the only one")
    void aTenantSmuggledInByTheCallerIsReplaced() {
        var bound = UUID.randomUUID();
        var smuggled = new Metadata();
        smuggled.put(TenantMetadata.KEY, UUID.randomUUID().toString());
        // The attaching interceptor runs first, the Tenant interceptor after it, nearest the wire.
        var channelWithBoth = ClientInterceptors.intercept(
                ClientInterceptors.intercept(channel, new TenantClientInterceptor(() -> Optional.of(bound))),
                MetadataUtils.newAttachHeadersInterceptor(smuggled));

        assertThat(ClientCalls.blockingUnaryCall(channelWithBoth, WHO_AM_I, CallOptions.DEFAULT, "?")).isEqualTo(bound.toString());
    }

    @Test
    @DisplayName("a call carrying two Tenants is UNAUTHENTICATED: the server never picks one")
    void twoTenantsAreUnauthenticated() {
        var headers = new Metadata();
        headers.put(TenantMetadata.KEY, UUID.randomUUID().toString());
        headers.put(TenantMetadata.KEY, UUID.randomUUID().toString());

        assertThatThrownBy(() -> ClientCalls.blockingUnaryCall(
                ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(headers)), WHO_AM_I, CallOptions.DEFAULT, "?"))
                .isInstanceOfSatisfying(StatusRuntimeException.class, refused ->
                        assertThat(refused.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
        assertThat(reached).hasValue(0);
    }

    private static final class Text implements MethodDescriptor.Marshaller<String> {
        @Override
        public InputStream stream(String value) {
            return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String parse(InputStream stream) {
            try {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
