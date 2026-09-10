# Spring Boot 4 on Java 21

Built on Spring Boot 4.1.x, Spring Framework 7, Hibernate ORM 7, Java 21.

The decision is about support, not novelty. Spring Boot 3.5's free patches stopped on 2026-06-30 and
there is no 3.6 — 3.5 is the final 3.x line, with the extended window reserved for commercial
customers. Starting a project today on 3.x means starting on a line that will never receive another
free security fix, which is visible in the `pom.xml` before a reader reaches any code. Spring gRPC,
which this design requires, is GA only on Boot 4; the Boot 3.x line stops at a pre-GA 0.12.0 with no
successor.

Starting on 3.5 and migrating later was rejected as the worst of the three: the migration consumes
effort and produces nothing to show for it, and because the gRPC library differs across the boundary
the entire service seam would be built twice.

## The risks, and what to do about them in week one

**Spike `pg_backend_pid()` before building on it.** Framework 7's transactional `StatelessSession`
appears to share the transaction's connection — which is the load-bearing fact for tenant isolation,
since a session on a different connection has no `app.current_tenant` and reads empty under
fail-closed policies. Spring's source says it shares; no document states the guarantee end to end
under the default `JpaTransactionManager`. Compare the backend pid across both paths and find out.

**Turn the second-level cache off explicitly.** On ORM 7 `StatelessSession` uses it *by default*, and
a cached entity outlives the Tenant context that authorised loading it (`docs/research/rls-pooling.md`).
This default silently undoes the isolation work.

**Budget for Testcontainers 2.x**, which renamed every artifact and package.

Java 21 is a fixed requirement of the project and is not revisited here, though Java 25 is LTS and
carries JEP 491, which would improve the virtual-threads story.
