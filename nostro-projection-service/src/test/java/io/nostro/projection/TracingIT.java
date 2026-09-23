package io.nostro.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.grpc.ClientInterceptors;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.nostro.balance.v1.BalanceServiceGrpc;
import io.nostro.balance.v1.GetBalanceRequest;
import io.nostro.domain.AccountId;
import io.nostro.grpc.TenantClientInterceptor;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;

/**
 * The projection's half of "one trace spans HTTP to gRPC to the projection's database": a call that
 * arrives carrying a trace is served in that trace, down to the JDBC statements that answer it. The
 * API service's half — its HTTP request's trace is the one the call carries — is
 * {@code ObservabilityIT.oneTraceCrossesTheGrpcBoundary}.
 */
class TracingIT extends ProjectionIntegrationTest {

    @LocalGrpcServerPort
    int port;

    @Test
    @DisplayName("a Balance call carrying a trace is served in it: the gRPC server span and the database's spans share the caller's trace id")
    void theCallersTraceReachesTheDatabase() throws Exception {
        var tenant = newTenant();
        var cash = AccountId.random();
        var entry = entry(tenant, AccountId.random(), cash, "USD", 42);
        publish(entry);
        awaitWatermark(tenant, entry.createdAt());
        var random = new Random();
        var traceId = HexFormat.of().formatHex(bytes(random, 16));
        var headers = new Metadata();
        headers.put(Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER),
                "00-" + traceId + "-" + HexFormat.of().formatHex(bytes(random, 8)) + "-01");

        var channel = Grpc.newChannelBuilderForAddress("localhost", port, InsecureChannelCredentials.create()).build();
        try {
            var traced = ClientInterceptors.intercept(channel,
                    new TenantClientInterceptor(() -> Optional.of(tenant.value())), MetadataUtils.newAttachHeadersInterceptor(headers));
            var answer = BalanceServiceGrpc.newBlockingStub(traced).getBalance(GetBalanceRequest.newBuilder().setAccountId(cash.toString()).build());
            assertThat(answer.getAmountMinor()).isEqualTo(42);
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }

        await().untilAsserted(() -> {
            var spans = CapturedSpans.ofTrace(traceId);
            assertThat(spans).as("spans of the caller's trace").anySatisfy(span -> {
                assertThat(span.kind()).isEqualTo("SERVER");
                assertThat(span.name()).containsIgnoringCase("GetBalance");
            });
            assertThat(spans).as("a database span in the caller's trace").anySatisfy(span ->
                    assertThat(span.name()).isIn("query", "connection", "result-set"));
        });
    }

    private static byte[] bytes(Random random, int length) {
        var bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }
}
