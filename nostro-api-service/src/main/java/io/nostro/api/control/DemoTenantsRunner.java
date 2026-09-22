package io.nostro.api.control;

import io.nostro.api.ApiVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Seeds the demo Tenants at startup and prints their credentials to the console, so that a stranger
 * with the compose stack up can demonstrate the refusals with nothing to configure (ADR-0015). Only
 * when {@code nostro.control.seed-demo-tenants} is on, which the compose file sets and nothing else
 * does: a printed credential is a dev-stack convenience, never a deployment's.
 */
@Component
@ConditionalOnProperty(name = "nostro.control.seed-demo-tenants", havingValue = "true")
class DemoTenantsRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoTenantsRunner.class);

    private final DemoTenants demoTenants;

    DemoTenantsRunner(DemoTenants demoTenants) {
        this.demoTenants = demoTenants;
    }

    @Override
    public void run(ApplicationArguments args) {
        var banner = new StringBuilder("\n\n");
        banner.append("================================ demo Tenants ================================\n");
        for (DemoTenants.Seeded seeded : demoTenants.seed()) {
            banner.append('\n').append(seeded.name()).append("  tenant ").append(seeded.tenant().value()).append('\n');
            seeded.key().ifPresentOrElse(
                    key -> banner.append("  Authorization: Bearer ").append(key.value()).append('\n'),
                    () -> banner.append("  already seeded; its key was printed when it was issued. Issue another with the control key:\n")
                            .append("  POST " + ApiVersion.V1 + "/control/tenants/").append(seeded.tenant().value()).append("/api-keys\n"));
        }
        banner.append('\n')
                .append("Each key reads and writes its own Tenant's ledger and sees nothing of the other's.\n")
                .append("==============================================================================\n");
        log.info("{}", banner);
    }
}
