# The Tenant comes from the credential, never from the request

A caller's Tenant is derived from the credential they present. It is never read from a header, a
path segment, a query parameter or a request body. A request that names a Tenant it was not issued
for receives nothing, not something. The one exception is the control plane (ADR-0015), whose
credential acts for no Tenant and so names the one it administers in the path.

Two credential kinds share one Spring Security filter chain, because the two populations genuinely
differ: machine callers hold long-lived opaque API keys, stored hashed and resolved per request,
because a ledger is written to by other systems far more than by people; staff hold short-lived
JWTs issued by a login endpoint.

An external identity provider (Keycloak in the compose file) was rejected: it is a heavy container
and a pile of realm configuration that is not the subject of this project, and it puts a startup
dependency in front of `docker compose up`, which must remain the whole first run.

## Consequences

Authorization fails closed at **startup**, not at request time: an endpoint that declares no required
permission fails the application's boot. Refusing to start is a stronger guarantee than refusing a
request, because it cannot be reached only on an untested path.

This is what makes the row-level security work meaningful rather than ornamental, and it deserves a
test of exactly that shape — a valid credential for one Tenant, a request naming another, and no
rows either way.
