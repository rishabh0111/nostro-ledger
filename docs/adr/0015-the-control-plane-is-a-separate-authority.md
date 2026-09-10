# The control plane is a separate authority

Tenants and credentials are created through a small control-plane API — create a Tenant, issue a
credential, revoke one — authenticated by a distinct bootstrap credential. Seeding Tenants only via
migration was rejected because a stranger could then not create one, which makes the isolation
demonstration look staged; self-service signup was rejected as a product, which ADR-0002 excludes.

The control plane is **the one place in the system that legitimately operates across Tenants**, and
that is modelled rather than left as a hole. The `tenant` table is not tenant-scoped, and
control-plane endpoints connect as a role distinct from the request-path role of ADR-0006.

## Consequences

**A control-plane credential cannot reach a ledger endpoint, and a ledger credential cannot reach a
control-plane endpoint**, and there is a test for each direction. Isolation enforced between the
system's own two surfaces is more convincing than isolation only enforced between customers.

`docker compose up` finishes with two Tenants seeded and their credentials printed, so the refusals
can be demonstrated the moment the stack is up, by anyone, with nothing to configure.
