package io.nostro.projection;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.nostro.balance.v1.BalanceServiceGrpc;
import io.nostro.balance.v1.GetBalanceRequest;
import io.nostro.balance.v1.GetBalanceResponse;
import io.nostro.domain.AccountId;
import io.nostro.domain.Position;
import io.nostro.domain.TenantId;
import io.nostro.grpc.TenantMetadata;
import io.nostro.grpc.TenantServerInterceptor;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.grpc.server.service.GrpcService;

/**
 * The projection's answer to "what is this Account's Balance", for the Tenant the call carries.
 *
 * <p>The Tenant comes only from the call's metadata, bound by {@link TenantServerInterceptor}, which
 * this service and no other is wrapped in: the standard health service beside it answers compose's
 * healthcheck, which is no Tenant's.
 *
 * <p><b>A minimum Position is a bounded wait, never a refusal</b> (ADR-0007). If the Tenant's
 * watermark is below it, the call parks on {@link Watermarks} — holding no JDBC connection, so a
 * lagging projection costs parked calls rather than the pool — until the watermark gets there or the
 * wait runs out. The wait is capped here, by the server, and by the call's own deadline, whichever
 * is sooner; then the answer is read and returned with the Position it actually reflects.
 */
@GrpcService(interceptors = TenantServerInterceptor.class)
class BalanceGrpcService extends BalanceServiceGrpc.BalanceServiceImplBase {

    /** Kept back from the caller's deadline for the read that follows the wait, and the answer's trip home. */
    private static final Duration ANSWER_MARGIN = Duration.ofMillis(100);

    private final ProjectedBalances balances;
    private final Watermarks watermarks;
    private final Duration maxWait;

    BalanceGrpcService(ProjectedBalances balances, Watermarks watermarks, ProjectionProperties properties) {
        this.balances = balances;
        this.watermarks = watermarks;
        this.maxWait = properties.balance().maxWait();
    }

    @Override
    public void getBalance(GetBalanceRequest request, StreamObserver<GetBalanceResponse> response) {
        var tenant = new TenantId(TenantMetadata.current().orElseThrow(() ->
                Status.UNAUTHENTICATED.withDescription("no Tenant on the call").asRuntimeException()));
        var account = new AccountId(parse(request.getAccountId()));
        Optional<Position> minimum = request.getMinPosition().isEmpty()
                ? Optional.empty()
                : Optional.of(Position.parse(request.getMinPosition()).orElseThrow(() ->
                        Status.INVALID_ARGUMENT.withDescription("min_position is not a Position token").asRuntimeException()));

        var projected = balances.read(tenant, account);
        if (minimum.isPresent() && !reflects(projected.watermark(), minimum.get())) {
            awaitWatermark(tenant, minimum.get());
            projected = balances.read(tenant, account);
        }

        var answer = GetBalanceResponse.newBuilder().setAmountMinor(projected.amountMinor());
        projected.currency().ifPresent(answer::setCurrency);
        projected.watermark().ifPresent(watermark -> answer.setPosition(watermark.token()));
        response.onNext(answer.build());
        response.onCompleted();
    }

    private void awaitWatermark(TenantId tenant, Position minimum) {
        var wait = maxWait;
        var deadline = Context.current().getDeadline();
        if (deadline != null) {
            var left = Duration.ofNanos(deadline.timeRemaining(TimeUnit.NANOSECONDS)).minus(ANSWER_MARGIN);
            wait = left.compareTo(wait) < 0 ? left : wait;
        }
        if (wait.isNegative() || wait.isZero()) {
            return;
        }
        try {
            watermarks.awaitAtLeast(tenant, minimum, wait);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw Status.CANCELLED.withDescription("interrupted while waiting for the watermark").asRuntimeException();
        }
    }

    /** Whether a watermark reflects the minimum. One from another installation reflects nothing the caller asked about. */
    private static boolean reflects(Optional<Position> watermark, Position minimum) {
        return watermark.filter(w -> w.installation() == minimum.installation() && w.isAtLeast(minimum)).isPresent();
    }

    private static UUID parse(String accountId) {
        try {
            return UUID.fromString(accountId);
        } catch (IllegalArgumentException notAUuid) {
            throw Status.INVALID_ARGUMENT.withDescription("account_id is not an Account id").asRuntimeException();
        }
    }
}
