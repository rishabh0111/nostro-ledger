package io.nostro.api;

import io.grpc.Context;
import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.nostro.balance.v1.BalanceServiceGrpc;
import io.nostro.balance.v1.GetBalanceRequest;
import io.nostro.balance.v1.GetBalanceResponse;
import io.nostro.domain.Position;
import io.nostro.grpc.TenantMetadata;
import io.nostro.grpc.TenantServerInterceptor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The projection, as the API service's own tests meet it: a real gRPC server on a real port, behind
 * the real {@link TenantServerInterceptor}, answering from the ledger's own Postings with no lag.
 * Not a mocked bean — that would be a second Spring context (ADR-0012) — and not in-process, whose
 * transport would skip the metadata that carries the Tenant.
 *
 * <p>It stands in for the projection's contract, not its mechanism: the projection service is tested
 * at its own seam, and the two meet for real only in the end-to-end test. Two switches let a test
 * make it behave badly: a Tenant can be <em>stalled</em>, so the projection stops at the Position it
 * had, and waits as the real one does for a minimum it will not reach; or made <em>unreachable</em>,
 * so every call is {@code UNAVAILABLE}.
 */
public final class StandInProjection extends BalanceServiceGrpc.BalanceServiceImplBase {

    /** The real projection's default cap on waiting for a minimum Position. */
    static final Duration MAX_WAIT = Duration.ofSeconds(1);

    private static final Metadata.Key<String> TRACEPARENT = Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER);

    private final JdbcClient ledger;
    private volatile Long installation;
    private final Map<UUID, Long> stalledAt = new ConcurrentHashMap<>();
    private final Set<UUID> unreachable = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> slowCalls = new ConcurrentHashMap<>();
    private final List<UUID> callers = new CopyOnWriteArrayList<>();
    private final List<String> traceparents = new CopyOnWriteArrayList<>();
    private final Server server;

    StandInProjection(JdbcClient ledgerAsOwner) {
        this.ledger = ledgerAsOwner;
        try {
            this.server = Grpc.newServerBuilderForPort(0, InsecureServerCredentials.create())
                    .addService(ServerInterceptors.intercept(this, new TenantServerInterceptor(), new TraceparentRecorder()))
                    .build()
                    .start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    int port() {
        return server.getPort();
    }

    /** The Tenant each call arrived with, in order: what the API service's client interceptor sent. */
    public List<UUID> callers() {
        return List.copyOf(callers);
    }

    /** The W3C {@code traceparent} each call arrived with, in order, or empty where none was sent. */
    public List<String> traceparents() {
        return List.copyOf(traceparents);
    }

    /** From now on the Tenant's projection reflects what it reflects now, and nothing recorded after. */
    public void stall(UUID tenant) {
        stalledAt.put(tenant, newest(tenant));
    }

    public void makeUnreachable(UUID tenant) {
        unreachable.add(tenant);
    }

    /** The Tenant's next {@code calls} calls answer only after the caller's deadline has passed, as a cold server's might. */
    public void slowDown(UUID tenant, int calls) {
        slowCalls.put(tenant, calls);
    }

    /** Undoes both switches for the Tenant. */
    public void recover(UUID tenant) {
        stalledAt.remove(tenant);
        unreachable.remove(tenant);
        slowCalls.remove(tenant);
    }

    @Override
    public void getBalance(GetBalanceRequest request, StreamObserver<GetBalanceResponse> response) {
        var tenant = TenantMetadata.current().orElseThrow();
        callers.add(tenant);
        if (unreachable.contains(tenant)) {
            response.onError(Status.UNAVAILABLE.withDescription("the stand-in projection is down for this Tenant").asRuntimeException());
            return;
        }
        if (takeASlowCall(tenant)) {
            sleepPastTheDeadline();
        }
        long watermark = Optional.ofNullable(stalledAt.get(tenant)).orElseGet(() -> newest(tenant));
        if (!request.getMinPosition().isEmpty() && Position.parse(request.getMinPosition()).orElseThrow().xid8() > watermark) {
            // As the real one does: wait, capped by the server and by the call's deadline, then answer anyway.
            parkUntilCapOrDeadline();
        }
        long amount = ledger.sql("""
                        SELECT coalesce(sum(amount_minor), 0)::bigint FROM posting
                         WHERE tenant_id = ? AND account_id = ? AND position <= CAST(? AS xid8)
                        """)
                .params(tenant, UUID.fromString(request.getAccountId()), Long.toUnsignedString(watermark))
                .query(Long.class)
                .single();
        var answer = GetBalanceResponse.newBuilder().setAmountMinor(amount);
        if (watermark > 0) {
            answer.setPosition(new Position(installation(), watermark).token());
        }
        response.onNext(answer.build());
        response.onCompleted();
    }

    /** Read at the first answer, not at start: the stand-in may start before Flyway has created the row. */
    private long installation() {
        if (installation == null) {
            installation = ledger.sql("SELECT system_identifier FROM installation").query(Long.class).single();
        }
        return installation;
    }

    private long newest(UUID tenant) {
        return ledger.sql("SELECT e.position::text FROM entry e WHERE e.tenant_id = ? ORDER BY e.position DESC LIMIT 1")
                .param(tenant)
                .query(String.class)
                .optional()
                .map(Long::parseUnsignedLong)
                .orElse(0L);
    }

    /** Whether this call is one of the slow ones asked for, counting it off if so. */
    private boolean takeASlowCall(UUID tenant) {
        var taken = new AtomicBoolean();
        slowCalls.computeIfPresent(tenant, (t, left) -> {
            taken.set(true);
            return left > 1 ? left - 1 : null;
        });
        return taken.get();
    }

    private static void sleepPastTheDeadline() {
        long wait = Optional.ofNullable(Context.current().getDeadline())
                .map(deadline -> deadline.timeRemaining(TimeUnit.MILLISECONDS) + 200)
                .orElse(MAX_WAIT.toMillis());
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void parkUntilCapOrDeadline() {
        long wait = MAX_WAIT.toMillis();
        var deadline = Context.current().getDeadline();
        if (deadline != null) {
            wait = Math.min(wait, deadline.timeRemaining(TimeUnit.MILLISECONDS) - 100);
        }
        try {
            Thread.sleep(Math.max(wait, 0));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Records the trace context a call carried, as a real server's tracing would read it. */
    private final class TraceparentRecorder implements ServerInterceptor {
        @Override
        public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
            var traceparent = headers.get(TRACEPARENT);
            traceparents.add(traceparent == null ? "" : traceparent);
            return next.startCall(call, headers);
        }
    }
}
