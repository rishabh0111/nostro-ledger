package io.nostro.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.nostro.balance.v1.BalanceServiceGrpc;
import io.nostro.balance.v1.GetBalanceRequest;
import io.nostro.balance.v1.GetBalanceResponse;
import io.nostro.domain.AccountId;
import io.nostro.domain.Position;
import io.nostro.domain.TenantId;
import io.nostro.grpc.TenantClientInterceptor;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;

/**
 * The projection's gRPC seam, over the real Netty server on a real port: what it answers, for whom,
 * and how long it will wait for a Position before answering anyway.
 */
class BalanceServiceIT extends ProjectionIntegrationTest {

    @LocalGrpcServerPort
    int port;

    @Autowired
    HikariDataSource pool;

    private ManagedChannel channel;

    @BeforeEach
    void connect() {
        channel = Grpc.newChannelBuilderForAddress("localhost", port, InsecureChannelCredentials.create()).build();
    }

    @AfterEach
    void disconnect() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("a call carrying no Tenant metadata is refused UNAUTHENTICATED, never answered as nobody's")
    void aCallWithNoTenantIsRefused() {
        var anonymous = BalanceServiceGrpc.newBlockingStub(channel);

        assertThatThrownBy(() -> anonymous.getBalance(request(AccountId.random(), Optional.empty())))
                .isInstanceOfSatisfying(StatusRuntimeException.class, refused ->
                        assertThat(refused.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
    }

    @Test
    @DisplayName("the health service answers compose's healthcheck without a Tenant")
    void healthNeedsNoTenant() {
        var health = HealthGrpc.newBlockingStub(channel).check(HealthCheckRequest.getDefaultInstance());

        assertThat(health.getStatus()).isEqualTo(HealthCheckResponse.ServingStatus.SERVING);
    }

    @Test
    @DisplayName("the Balance comes back with its Currency and the Position of the Tenant's watermark")
    void theBalanceAndThePositionItReflects() {
        var tenant = newTenant();
        var cash = AccountId.random();
        publish(entry(tenant, AccountId.random(), cash, "GBP", 4_200));
        var last = entry(tenant, cash, AccountId.random(), "GBP", 200);
        publish(last);
        awaitWatermark(tenant, last.createdAt());

        var answer = as(tenant).getBalance(request(cash, Optional.empty()));

        assertThat(answer.getAmountMinor()).isEqualTo(4_000);
        assertThat(answer.getCurrency()).isEqualTo("GBP");
        assertThat(answer.getPosition()).isEqualTo(last.createdAt().token());
    }

    @Test
    @DisplayName("over the wire, another Tenant's Account is an Account with no Postings, and the answer reflects only the caller's history")
    void anotherTenantsAccountIsAnAccountWithNoPostings() {
        var a = newTenant();
        var b = newTenant();
        var cashOfA = AccountId.random();
        var ofA = entry(a, AccountId.random(), cashOfA, "USD", 999);
        var ofB = entry(b, AccountId.random(), AccountId.random(), "USD", 1);
        publish(ofB);
        publish(ofA);
        awaitWatermark(a, ofA.createdAt());
        awaitWatermark(b, ofB.createdAt());

        var answer = as(b).getBalance(request(cashOfA, Optional.empty()));

        assertThat(answer.getAmountMinor()).isZero();
        assertThat(answer.getCurrency()).isEmpty();
        assertThat(answer.getPosition()).isEqualTo(ofB.createdAt().token());
    }

    @Test
    @DisplayName("a minimum Position not yet reached is waited for, and the answer includes it as soon as it is applied")
    void aMinimumNotYetReachedIsWaitedFor() throws Exception {
        var tenant = newTenant();
        var cash = AccountId.random();
        var first = entry(tenant, AccountId.random(), cash, "USD", 100);
        publish(first);
        awaitWatermark(tenant, first.createdAt());
        var next = entry(tenant, AccountId.random(), cash, "USD", 50);

        var started = Instant.now();
        var answer = CompletableFuture.supplyAsync(() -> as(tenant).getBalance(request(cash, Optional.of(next.createdAt()))));
        Thread.sleep(200);
        publish(next);

        var reflected = answer.get(5, TimeUnit.SECONDS);
        assertThat(reflected.getAmountMinor()).isEqualTo(150);
        assertThat(Position.parse(reflected.getPosition()).orElseThrow().isAtLeast(next.createdAt())).isTrue();
        assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("a minimum Position never reached is answered at the server's cap, with the Position actually reflected: lag is disclosed, not refused")
    void aMinimumNeverReachedIsAnsweredAtTheCap() {
        var tenant = newTenant();
        var cash = AccountId.random();
        var only = entry(tenant, AccountId.random(), cash, "USD", 100);
        publish(only);
        awaitWatermark(tenant, only.createdAt());
        var unreachable = new Position(INSTALLATION, only.createdAt().xid8() + 1_000_000);

        var started = Instant.now();
        var answer = as(tenant).withDeadlineAfter(10, TimeUnit.SECONDS).getBalance(request(cash, Optional.of(unreachable)));
        var waited = Duration.between(started, Instant.now());

        assertThat(answer.getAmountMinor()).isEqualTo(100);
        assertThat(answer.getPosition()).isEqualTo(only.createdAt().token());
        // The server's cap (1s by default), not the caller's ten-second deadline.
        assertThat(waited).isBetween(Duration.ofMillis(900), Duration.ofMillis(3_000));
    }

    @Test
    @DisplayName("a caller's deadline shorter than the server's cap bounds the wait, and the call still answers rather than timing out")
    void theCallersDeadlineBoundsTheWait() {
        var tenant = newTenant();
        var cash = AccountId.random();
        var only = entry(tenant, AccountId.random(), cash, "USD", 3);
        publish(only);
        awaitWatermark(tenant, only.createdAt());

        var answer = as(tenant).withDeadlineAfter(400, TimeUnit.MILLISECONDS)
                .getBalance(request(cash, Optional.of(new Position(INSTALLATION, only.createdAt().xid8() + 1_000_000))));

        assertThat(answer.getPosition()).isEqualTo(only.createdAt().token());
    }

    @Test
    @DisplayName("calls parked on a lagging watermark hold no connection: forty of them, a pool of ten, and an ordinary read is still answered at once")
    void parkedCallsHoldNoConnection() throws Exception {
        var tenant = newTenant();
        var cash = AccountId.random();
        var only = entry(tenant, AccountId.random(), cash, "USD", 5);
        publish(only);
        awaitWatermark(tenant, only.createdAt());
        var unreachable = Optional.of(new Position(INSTALLATION, only.createdAt().xid8() + 1_000_000));
        assertThat(pool.getMaximumPoolSize()).isLessThan(40);
        int platformThreadsBefore = ManagementFactory.getThreadMXBean().getThreadCount();

        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var parked = new ArrayList<CompletableFuture<GetBalanceResponse>>();
            for (int i = 0; i < 40; i++) {
                parked.add(CompletableFuture.supplyAsync(() -> as(tenant).getBalance(request(cash, unreachable)), callers));
            }
            Thread.sleep(300);

            assertThat(pool.getHikariPoolMXBean().getActiveConnections()).as("connections held while parked").isZero();
            assertThat(ManagementFactory.getThreadMXBean().getThreadCount() - platformThreadsBefore)
                    .as("platform threads spent on parked calls: they are virtual").isLessThan(20);
            var started = Instant.now();
            var ordinary = as(tenant).getBalance(request(cash, Optional.empty()));
            assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofMillis(500));
            assertThat(ordinary.getAmountMinor()).isEqualTo(5);

            for (var call : parked) {
                assertThat(call.get(10, TimeUnit.SECONDS).getPosition()).isEqualTo(only.createdAt().token());
            }
        }
    }

    private BalanceServiceGrpc.BalanceServiceBlockingStub as(TenantId tenant) {
        return BalanceServiceGrpc.newBlockingStub(channel)
                .withInterceptors(new TenantClientInterceptor(() -> Optional.of(tenant.value())));
    }

    private static GetBalanceRequest request(AccountId account, Optional<Position> minimum) {
        var request = GetBalanceRequest.newBuilder().setAccountId(account.toString());
        minimum.ifPresent(position -> request.setMinPosition(position.token()));
        return request.build();
    }
}
