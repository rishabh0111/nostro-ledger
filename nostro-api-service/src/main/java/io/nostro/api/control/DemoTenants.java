package io.nostro.api.control;

import io.nostro.api.auth.ApiKey;
import io.nostro.api.auth.Permission;
import io.nostro.domain.TenantId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The two demo Tenants {@code docker compose up} ends with (ADR-0015), made through the control
 * plane's own code path rather than a fixture, so that what the demo shows is what a stranger
 * would get by calling the control plane themselves.
 *
 * <p>Idempotent: a Tenant that exists is left as it is and gets no new key, because the key was
 * printed once, when it was issued, and printing it again would mean having kept it.
 */
@Component
public class DemoTenants {

    public static final List<String> NAMES = List.of("demo-alpha", "demo-beta");

    static final String LABEL = "demo";

    private final ControlPlane controlPlane;

    DemoTenants(ControlPlane controlPlane) {
        this.controlPlane = controlPlane;
    }

    /** Creates whichever demo Tenants are absent and issues each a ledger key; the ones present are reported without a key. */
    public List<Seeded> seed() {
        List<Seeded> seeded = new ArrayList<>();
        for (String name : NAMES) {
            seeded.add(switch (controlPlane.createTenant(name)) {
                case ControlPlane.TenantOutcome.Created created -> new Seeded(name, created.tenant().id(), Optional.of(issue(created.tenant().id())));
                case ControlPlane.TenantOutcome.NameTaken taken -> new Seeded(name, controlPlane.findTenant(name).orElseThrow().id(), Optional.empty());
                case ControlPlane.TenantOutcome.Invalid invalid -> throw new IllegalStateException(invalid.reason());
            });
        }
        return List.copyOf(seeded);
    }

    private ApiKey issue(TenantId tenant) {
        return switch (controlPlane.issueApiKey(tenant, LABEL, Set.of(Permission.LEDGER_READ, Permission.LEDGER_WRITE))) {
            case ControlPlane.IssueOutcome.Issued issued -> issued.apiKey().key();
            case ControlPlane.IssueOutcome.UnknownTenant unknown -> throw new IllegalStateException("the Tenant was just created");
            case ControlPlane.IssueOutcome.Invalid invalid -> throw new IllegalStateException(invalid.reason());
        };
    }

    /** @param key the key issued on this run, or empty if the Tenant already existed */
    public record Seeded(String name, TenantId tenant, Optional<ApiKey> key) {
    }
}
