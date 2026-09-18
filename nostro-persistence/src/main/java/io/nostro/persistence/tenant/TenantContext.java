package io.nostro.persistence.tenant;

import io.nostro.domain.TenantId;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The Tenant the current thread is acting for, as established from the validated credential
 * (ADR-0006). Read by {@link TenantContextTransactionListener} at the start of every transaction
 * and turned into {@code app.current_tenant} on the transaction's own connection.
 *
 * <p>Absent context is not an error here. It becomes one at the database: with no Tenant set,
 * every row-level security policy matches nothing, so reads return no rows and writes are refused.
 * That is the fail-closed direction and it is deliberate.
 */
public final class TenantContext {

    private static final ThreadLocal<TenantId> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static Optional<TenantId> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * Binds the Tenant for the current thread until the returned scope is closed. Nesting is
     * refused: a request acts for exactly one Tenant, and a second binding is a bug.
     */
    public static Scope bind(TenantId tenant) {
        Objects.requireNonNull(tenant, "tenant");
        if (CURRENT.get() != null) {
            throw new IllegalStateException("tenant context is already bound to " + CURRENT.get());
        }
        CURRENT.set(tenant);
        return CURRENT::remove;
    }

    public static <T> T runAs(TenantId tenant, Supplier<T> work) {
        try (var ignored = bind(tenant)) {
            return work.get();
        }
    }

    public static void runAs(TenantId tenant, Runnable work) {
        try (var ignored = bind(tenant)) {
            work.run();
        }
    }

    /** Closing unbinds. Never throws. */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
