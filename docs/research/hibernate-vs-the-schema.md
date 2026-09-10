# Hibernate 6 against a composite-key, RLS-guarded, insert-only schema

Research note on the specific places where JPA/Hibernate 6.x fights the schema this project has
already committed to: `(tenant_id, id)` composite keys, composite `(tenant_id, parent_id)` foreign
keys, insert-only core tables, application-assigned UUIDs, and row-level security whose tenant context
is transaction-local (see [`rls-pooling.md`](rls-pooling.md)).

JPA is fixed and not up for renegotiation. So the useful output is not "use jOOQ" — it is: for each
point of friction, what is the documented mechanism, what does it silently fail to do, and where does
the real guarantee have to live instead.

Investigated against primary sources (Hibernate 6.6 User Guide and Introduction, Hibernate ORM javadoc
and changelog, Spring Data JPA and Spring Framework reference docs, Jakarta Persistence 3.1). Every
non-obvious claim is cited inline; see [Sources](#sources). Three claims are marked **unverified** —
they need a test in this repo, not more reading.

---

## Executive summary

- **The composite FK works, via `@JoinColumns` with `insertable=false, updatable=false` on the shared
  `tenant_id`.** `@MapsId` is *not* the mechanism — partial column overlap is not a derived identity.
  Without the read-only flags Hibernate 6.6 refuses to boot, with a precise and quotable error (§1).
- **The cost of that mechanism is that `tenant_id` acquires two independent writers in Java** — the
  `@EmbeddedId` writes it, the association only reads it — **and nothing in JPA checks they agree**.
  Construct the child id from the parent; never by hand (§1).
- **Use `@EmbeddedId` with a Java `record`.** This is Hibernate's own stated recommendation, not a
  matter of taste: *"This is not our preferred approach"* (of `@IdClass`) … *"we recommend that the
  `BookId` class be declared as an `@Embeddable` type"*. The record must still
  `implements Serializable` — the spec requires it, and records do not get it for free (§2, §3).
- **Records as `@Embeddable`: Hibernate ORM 6.2.** Records as `@EmbeddedId`: documented in the 6.6
  Introduction guide as the recommended shape. But record-embeddable bugs were still being fixed in
  **6.6.0 and 6.6.1** — pin `>= 6.6.1.Final` and mean it (§3).
- **`@Immutable` is a performance hint, not a guard.** Updates are *"ignored, with no exception
  thrown."* It says nothing at all about `DELETE`. It is exactly the wrong shape for "fail loudly"
  (§4).
- **The insert-only barrier belongs in the database**, as `REVOKE UPDATE, DELETE` on the app role —
  which drops straight into the two-role split this project already has for RLS. That is the only
  guard that also covers HQL bulk DML, native SQL, and `StatelessSession` (§4).
- **The extra SELECT on assigned UUIDs is Spring Data's, not Hibernate's.** `EntityManager.persist()`
  does not SELECT; `save()` calls `merge()` when the id is non-null, and `merge()` does. Fix: don't
  call `save()` (§5).
- **`StatelessSession` is legitimate but has zero Spring transaction integration on Boot 3.x.** A
  no-arg `openStatelessSession()` inside a `@Transactional` method takes a *different* connection from
  the pool — one with no `app.current_tenant` — and under fail-closed RLS that is silent wrong data.
  Use `openStatelessSession(connection)` with the transaction's own connection, obtained via
  `session.doWork(…)`. Spring Framework 7 fixes this properly; Boot 3.x does not have it (§6).
- **`StatelessSession` does not batch** — its operations are *"always performed synchronously,
  resulting in immediate access to the database."* §6 and §7 are therefore in direct tension: you get
  the persistence context gone, or the batch, not both (§7).
- **Batching is available here precisely because the ids are assigned UUIDs.** IDENTITY generation
  transparently disables insert batching; nothing in this schema uses it (§7).

---

## 1. The composite foreign key with a shared `tenant_id`

The shape: `posting` has primary key `(tenant_id, id)` and foreign key
`(tenant_id, entry_id) -> entry (tenant_id, id)`. `tenant_id` is in the PK *and* in the FK. In JPA
terms, one database column is reachable through two mapped attributes.

### What Hibernate does if you say nothing

Hibernate 6.6 checks for column duplication at bootstrap, in
`org.hibernate.mapping.Value#checkColumnDuplication`, and the check is gated on writability:

```java
if ( isColumnInsertable( i ) || isColumnUpdateable( i ) ) {
    final Column col = (Column) selectable;
    if ( !distinctColumns.add( col.getName() ) ) {
        throw new MappingException(
                "Column '" + col.getName()
                        + "' is duplicated in mapping for " + owner
                        + " (use '@Column(insertable=false, updatable=false)' when mapping multiple properties to the same column)"
        );
    }
}
```

([hibernate-orm 6.6, `mapping/Value.java`](https://github.com/hibernate/hibernate-orm/blob/6.6/hibernate-core/src/main/java/org/hibernate/mapping/Value.java))

Two things follow, and both matter:

1. The failure is at **bootstrap**, not at runtime. Good — it is loud, and it names the column.
2. The check counts a column only if it is *insertable or updatable*. Marking one of the two mappings
   read-only is not a trick to dodge the check; it is the mechanism the check is asking for.

### The mapping

```java
@Embeddable
public record PostingId(UUID tenantId, UUID id) implements Serializable {}

@Entity
@Table(name = "posting")
public class Posting {

    @EmbeddedId
    private PostingId id;                 // owns tenant_id for INSERT

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
        // shared with the @EmbeddedId above: read-only on this side
        @JoinColumn(name = "tenant_id", referencedColumnName = "tenant_id",
                    insertable = false, updatable = false),
        @JoinColumn(name = "entry_id",  referencedColumnName = "id")
    })
    private Entry entry;                  // owns entry_id for INSERT
}
```

`insertable` and `updatable` are standard `@JoinColumn` elements — *"Whether the column is included in
SQL INSERT statements generated by the persistence provider"* / *"…UPDATE statements…"*, both
defaulting to `true`
([Jakarta Persistence 3.1, `@JoinColumn`](https://jakarta.ee/specifications/persistence/3.1/apidocs/jakarta.persistence/jakarta/persistence/joincolumn)).
`referencedColumnName` must be given explicitly on every column once there is more than one: the
default is *"the primary key column of the referenced table"*, and a two-column FK has no sensible
default.

### Why not `@MapsId`

`@MapsId` *"Designates a `ManyToOne` or `OneToOne` relationship attribute that provides the mapping for
an `EmbeddedId` primary key"*, and its value element is *"The name of the attribute within the
composite key to which the relationship attribute corresponds. If not supplied, the relationship maps
the entity's primary key."*
([Jakarta Persistence 3.1, `@MapsId`](https://jakarta.ee/specifications/persistence/3.1/apidocs/jakarta.persistence/jakarta/persistence/mapsid))

That is a statement about **one id attribute corresponding to the parent's whole primary key**. Here
the parent's primary key is itself composite `(tenant_id, id)`, and the child borrows only *one of its
two columns* — `tenant_id` — while `entry_id` is not part of the child's key at all. That is a partial
column overlap, not a derived identity, and `@MapsId` has no expression for it.

`@MapsId` *would* be right for a genuinely identifying child — a table whose PK is
`(tenant_id, entry_id, seq)`, modelled as an `@EmbeddedId` containing the parent's key type. If a table
in this schema ends up shaped that way, revisit. Otherwise the read-only-join-column form is the answer.

Hibernate does also permit `@ManyToOne` *inside* the `@EmbeddedId` class itself — the user guide's
"`@EmbeddedId` with `@ManyToOne`" example does exactly this — but flags it: *"that is not portably
supported by the Jakarta Persistence specification"*
([Hibernate 6.6 User Guide, Composite identifiers](https://docs.hibernate.org/orm/6.6/userguide/html_single/Hibernate_User_Guide.html#identifiers-composite-aggregated)).
Don't.

### Sharp edges

- **`tenant_id` now has two writers and one of them is muted.** `posting.setEntry(entry)` no longer
  contributes `tenant_id` to the INSERT — only `PostingId.tenantId` does. Set the association, forget
  the id, and you insert a null or a stale tenant. Nothing in JPA cross-checks them.
  **Mitigation:** never build `PostingId` by hand. Give it a factory that takes the parent —
  `PostingId.under(entry)` — so the two copies cannot diverge anywhere except that one method.
- **The database catches the divergence anyway, and that is the point.** `entry_id` *is* insertable,
  so a mismatched pair `(tenantA, entryOfTenantB)` reaches Postgres and the composite FK rejects it —
  the write barrier from [`rls-pooling.md` §4](rls-pooling.md). A wrong `tenant_id` is caught by the
  RLS `WITH CHECK`. Between them the Java-side aliasing is contained. This is the project's thesis
  working: the ORM's weakness is absorbed by a constraint rather than by a code review.
- **The association is read-only in the `tenant_id` position forever.** Re-pointing a Posting at an
  Entry in another tenant would not update `tenant_id`. On insert-only tables this is moot — a rare
  case of two constraints cancelling out.
- **Schema validation will not check any of this.** `ddl-auto=validate` runs
  `AbstractSchemaValidator`, which has `validateTable`, `validateColumnType` and `validateSequence`
  and no notion of a foreign key or a unique constraint
  ([hibernate-orm 6.6, `AbstractSchemaValidator`](https://github.com/hibernate/hibernate-orm/blob/6.6/hibernate-core/src/main/java/org/hibernate/tool/schema/internal/AbstractSchemaValidator.java)).
  Migrations own the constraints; Hibernate will confirm only that the columns exist.
- **The escape hatch, if this gets worse.** Map `entry_id` as a plain `@Column` and drop the
  association entirely, fetching the Entry by id when needed. You lose navigation and `JOIN FETCH`.
  For a leaf table on an insert-only write path that is a smaller loss than it sounds — but it is a
  real one, and it is the honest fallback rather than a cleverer mapping.

---

## 2. `@EmbeddedId` versus `@IdClass`

### The spec floor

Both forms must satisfy the same rules, which the Hibernate user guide restates from the Jakarta
Persistence spec:

> * The composite identifier must be represented by a "primary key class". […]
> * The primary key class must be public and must have a public no-arg constructor.
> * The primary key class must be serializable.
> * The primary key class must define equals and hashCode methods, consistent with equality for the
>   underlying database types to which the primary key is mapped.

([Hibernate 6.6 User Guide, Composite identifiers](https://docs.hibernate.org/orm/6.6/userguide/html_single/Hibernate_User_Guide.html#identifiers-composite))

**`Serializable` is the one people forget.** A Java record gets `equals`/`hashCode`/`toString` for free
but is *not* automatically `Serializable`. Write `implements Serializable`.

The no-arg constructor requirement is relaxed for records: *"Alternatively, an embeddable type may be
defined as a Java record type"* … *"the requirement for a constructor with no parameters is relaxed."*
([Hibernate 6.6 Introduction, Entities](https://docs.hibernate.org/orm/6.6/introduction/html_single/Hibernate_Introduction.html))

### Hibernate's recommendation is explicit

On `@IdClass`, having shown it first:

> This is not our preferred approach. Instead, we recommend that the `BookId` class be declared as an
> `@Embeddable` type […] This second approach eliminates some duplicated code.

and on satisfying `equals`/`hashCode`:

> Every such id class must override `equals()` and `hashCode()`. Of course, the easiest way to satisfy
> these requirements is to declare the id class as a `record`.

([Hibernate 6.6 Introduction, Entities](https://docs.hibernate.org/orm/6.6/introduction/html_single/Hibernate_Introduction.html))

Hibernate also notes it will accept multiple bare `@Id` attributes with no id class at all — a
non-standard extension — and calls it *"generally considered poor design"*. Ignore that door.

### What each actually costs

| | `@EmbeddedId PostingId id` | `@IdClass(PostingId.class)` + two `@Id` fields |
|---|---|---|
| Hibernate's position | **recommended** | *"not our preferred approach"* |
| Duplication | one declaration | fields declared twice (entity + id class) |
| `JpaRepository<Posting, PostingId>` | works | works |
| `findById(new PostingId(t, i))` | works | works |
| `getReferenceById(new PostingId(t, i))` | works | works |
| Derived query on tenant | `findById_TenantId(UUID t)` | `findByTenantId(UUID t)` — reads better |
| JPQL path | `p.id.tenantId` | `p.tenantId` |
| `equals`/`hashCode` | free, from the record | free, from the record |
| Record support maturity | good, see §3 | later and buggier — HHH-18062, HHH-18251 |

The one real ergonomic loss with `@EmbeddedId` is derived query method names. Spring Data resolves
`findByIdTenantId` by *"splitting up the source at the camel-case parts from the right side"*, and warns
that *"it is possible for the algorithm to select the wrong property"*; the documented remedy is the
explicit traversal separator — *"To resolve this ambiguity you can use `_` inside your method name"*
([Spring Data JPA, Property Expressions](https://docs.spring.io/spring-data/jpa/reference/repositories/query-methods-details.html)).

So: **write `findById_TenantId`, with the underscore, always.** It is uglier and it cannot be
misparsed. In a repository where every method is tenant-scoped, that consistency is worth more than the
aesthetics.

The `@IdClass` column is not merely a style preference — the record fixes landed later there
(`HHH-18062`, *"'Could not instantiate entity … due to argument type mismatch' with record @IdClass"*,
6.6.0.Final; `HHH-18251`, *"IdClass with Record not working with ToOne relation columns"*, 6.6.0.CR1 —
[changelog](https://github.com/hibernate/hibernate-orm/blob/6.6/changelog.txt)). Given this project
needs both records and composite ids, `@EmbeddedId` is the better-trodden path as well as the
recommended one.

**Verdict: `@EmbeddedId` with a `record … implements Serializable`.**

---

## 3. Java records as `@Embeddable`

### Version history, precisely

| Capability | Available from | Evidence |
|---|---|---|
| A record as an embeddable, via a hand-written `EmbeddableInstantiator` | 6.0 | user guide *"Custom instantiation"*: Jakarta Persistence *"requires embeddable classes to follow Java Bean conventions"* but *"not all value compositions … follow Java Bean conventions - e.g. a struct or Java 15 record"* |
| **`@Embeddable record` out of the box** | **6.2** | ORM 6.2 Final: *"Hibernate now support the mapping of Java records as embeddables."* |
| `@Embeddable record` + `@Struct` (a Postgres UDT) | 6.2 | same announcement |
| **A record as an `@EmbeddedId`** | documented in the **6.6** Introduction guide, as the recommended form | *"we recommend that the `BookId` class be declared as an `@Embeddable` type"*, with `@Embeddable record BookId(String isbn, int printing) {}` and `@EmbeddedId BookId bookId;` |

Directly to the question: `record Money(long minorUnits, String currencyCode) {}` annotated
`@Embeddable` and used as an entity field — **yes, from Hibernate ORM 6.2**, with no
`EmbeddableInstantiator` required. A record as `@EmbeddedId` — yes, and it is the shape the Hibernate
authors themselves write.

### The restrictions worth knowing

- **It must be `@Embeddable`, not `@Entity`.** Nothing in the Hibernate 6.6 docs offers records as
  entity types, and the entity lifecycle (proxies, dirty checking, bytecode enhancement) assumes
  mutable fields.
- **The feature was still settling as late as 6.6.1.** From the changelog:
  - `HHH-16759` — *"Merge fails when entity has an Embedded Java record"* — fixed **6.3.0.Final**
  - `HHH-18158` — *"EmbeddedId not working when Embedded is a generic record"* — fixed **6.6.0.CR1**
  - `HHH-18445` — *"Embeddable as Java Record has wrong order of columns"* — fixed **6.6.1.Final**

  `HHH-18445` is the one to note: wrong column *order* in a record embeddable is the class of bug that
  produces a silently transposed `Money(minorUnits, currencyCode)` rather than an exception.
  **Pin `hibernate.version` to `6.6.1.Final` or later explicitly in the `pom.xml`**, overriding the
  Spring Boot BOM if it resolves lower.
- **Keep record embeddables to plain values** — `Money`, `PostingId`, `Currency`; basic components
  only. **Unverified:** it is widely reported that a record embeddable containing a `@ManyToOne` or
  `@OneToMany` fails, because a record's `final` fields cannot be bytecode-enhanced. The Hibernate 6.6
  documentation does not state this either way. Since nothing in this schema needs it, treat it as
  unsupported and don't find out the hard way.
- **Unverified:** `Money` has a primitive `long` component. If an `@Embedded Money` is ever nullable
  and every column comes back null, the instantiator has no null to give a `long`. The docs do not
  address it. If any `Money`-typed column is nullable, write a test for the all-null read before
  trusting it. (Ledger amounts should be `NOT NULL` anyway, which sidesteps it.)
- Records also supply §2's `equals`/`hashCode` for free and make the id genuinely immutable, which is
  the correct semantics for a key — and a decent partial answer to "where is the Java in this project".

---

## 4. Insert-only entities: `@Immutable` is not the barrier

### What `@Immutable` actually says

> The `@Immutable` annotation declares something immutable in the **updateability** sense.

([Hibernate 6.6 User Guide, Mutability](https://docs.hibernate.org/orm/6.6/userguide/html_single/Hibernate_User_Guide.html#mutability-immutable))

and, from the annotation's own javadoc:

> **Immutable entities** — Changes made in memory to the state of an immutable entity are never
> synchronized to the database. **The changes are ignored, with no exception thrown.**
>
> **Immutable collections** — An immutable collection may not be modified. A
> `org.hibernate.HibernateException` is thrown if an element is added to or removed from the
> collection.

([hibernate-orm 6.6, `annotations/Immutable.java`](https://github.com/hibernate/hibernate-orm/blob/6.6/hibernate-core/src/main/java/org/hibernate/annotations/Immutable.java))

Read that asymmetry carefully. **Immutable collections throw. Immutable entities do not.** The user
guide says the same in its own words: *"When loading the entity and trying to change its state,
Hibernate will skip any modification, therefore no SQL `UPDATE` statement is executed."*

For this project that is the worst available behaviour: a mutation of an Entry in application code does
nothing, reports nothing, and leaves the developer believing it worked.

### What it does not cover

| Path | Stopped by `@Immutable`? |
|---|---|
| Dirty-checked UPDATE of a managed entity | Yes — silently, no exception |
| `EntityManager.remove(entity)` → DELETE | **The docs say nothing.** `@Immutable` is defined purely in the updateability sense; delete appears nowhere in the javadoc or in the User Guide's mutability chapter. Assume the DELETE proceeds. |
| HQL/JPQL bulk `update` / `delete` | Not addressed; bulk DML bypasses the persistence context by design |
| Native SQL, `session.doWork(…)`, `StatelessSession` | No — and the User Guide is explicit that *"operations performed via a stateless session bypass Hibernate's event model and interceptors"* |
| Anything outside this JVM (psql, another service, a migration) | No |

The delete row is a genuine gap in Hibernate's documentation, not a subtlety being glossed here. It
should be settled by a test in this repo — `remove()` an `@Immutable` Entry and assert what happens —
rather than by inference.

### Making it loud inside the JVM

If a Java-side error is wanted at all, the honest options are:

- **`@PreUpdate` / `@PreRemove` callbacks that throw.** Blunt, portable, loud. They fire for
  entity-lifecycle operations only.
- **`PreDeleteEventListener`** — the SPI is one method, *"Return true if the operation should be
  vetoed"*
  ([hibernate-orm 6.6, `PreDeleteEventListener`](https://github.com/hibernate/hibernate-orm/blob/6.6/hibernate-core/src/main/java/org/hibernate/event/spi/PreDeleteEventListener.java)).
  Note that *vetoing* is itself silent — to be loud you throw from the listener rather than returning
  `true`.
- **`@SQLDelete`** can replace the generated DELETE with arbitrary SQL, which could be made to raise.
  Cute, but it buries the intent in a SQL string and still only covers the entity-lifecycle path.

All three are in-JVM, which means all three are bypassed by most rows in the table above.

### The barrier

The real one is a privilege, and this project already has the role split to hang it on
([`rls-pooling.md` §3](rls-pooling.md)):

```sql
REVOKE UPDATE, DELETE ON entry, posting FROM app_user;
```

Postgres then rejects the statement with `permission denied for table posting` (SQLSTATE 42501). That
holds against dirty checking, bulk HQL, native SQL, `StatelessSession`, a stray psql session, and every
write path that has not been written yet. Migrations still run as the owner and can do what they need.

A second, complementary lever: RLS policies are per-command. With RLS enabled and **only** `FOR SELECT`
and `FOR INSERT` policies present, `UPDATE` and `DELETE` fall to PostgreSQL's default-deny
([`rls-pooling.md` §3](rls-pooling.md), citing
[PostgreSQL: Row Security Policies](https://www.postgresql.org/docs/current/ddl-rowsecurity.html)).
Belt and braces, and it costs nothing to write the policies that narrowly.

**Keep `@Immutable` anyway** — for the reason it exists: *"reducing memory footprint since there is no
need to retain the loaded state for the dirty checking mechanism"* and *"speeding-up the Persistence
Context flushing phase since immutable entities can skip the dirty checking process"*. Document it as
an optimisation and a statement of intent, never as the guarantee.

---

## 5. Assigned UUIDs and the extra SELECT

### First, correct the premise

`EntityManager.persist()` does **not** issue a SELECT for an assigned id. The Hibernate Introduction's
operation table is unambiguous: `persist(Object)` — *"Make a transient object persistent and schedule a
SQL `insert` statement for later execution"* — and *"Notice that `persist()` and `remove()` have no
immediate effect on the database, and instead simply schedule a command for later execution."*
`merge(Object)` is the one that must read: *"Copy the state of a given detached object to a
corresponding managed persistent instance and return the persistent object"* — obtaining that managed
instance is the SELECT.

So the extra SELECT is **Spring Data's**, and it comes from `save()`:

> If the entity has not yet been persisted, Spring Data JPA saves the entity with a call to the
> `entityManager.persist(…)` method. Otherwise, it calls the `entityManager.merge(…)` method.

with the default detection strategy:

> By default Spring Data JPA inspects first if there is a Version-property of non-primitive type. If
> there is, the entity is considered new if the value of that property is `null`. Without such a
> Version-property Spring Data JPA inspects the identifier property of the given entity. If the
> identifier property is `null`, then the entity is assumed to be new. Otherwise, it is assumed to be
> not new.

and the consequence spelled out:

> Option 1 is not an option for entities that use manually assigned identifiers and no version
> attribute as with those the identifier will always be non-`null`.

([Spring Data JPA, Persisting Entities](https://docs.spring.io/spring-data/jpa/reference/jpa/entity-persistence.html))

An application-generated `UUID` is never null. So `save()` on a brand-new Entry means `merge()`, which
means a SELECT that finds nothing, followed by the INSERT. Do that for the Entry and each Posting and a
one-Entry write becomes **1 + N pointless round trips**, each one also paying RLS policy evaluation.

### The fixes, in the order this project should prefer them

1. **Don't call `save()`.** Inject the `EntityManager` (or write a repository fragment) and call
   `persist()`. Zero extra SELECTs, no base class, no flag. And if the id somehow already exists, the
   INSERT fails on the primary key — which on an insert-only ledger is the correct, loud outcome rather
   than a silent update. This is the recommendation.
2. **`Persistable` with a transient flag**, if the repositories must stay `CrudRepository`-shaped.
   Spring Data documents the pattern exactly:

   ```java
   @MappedSuperclass
   public abstract class AbstractEntity<ID> implements Persistable<ID> {

     @Transient
     private boolean isNew = true;                 // (1)

     @Override
     public boolean isNew() {
       return isNew;                               // (2)
     }

     @PostPersist                                  // (3)
     @PostLoad
     void markNotNew() {
       this.isNew = false;
     }
   }
   ```

   ([Spring Data JPA, Persisting Entities](https://docs.spring.io/spring-data/jpa/reference/jpa/entity-persistence.html))
   The cost is a `@MappedSuperclass` and a piece of transient state on every entity — inheritance
   introduced solely to correct a framework heuristic.
3. **`@Version`** — a non-primitive version property that is null means new, per the strategy above.
   It works, and it is the wrong thing here: an optimistic-lock column on a table that is never updated
   is a column whose value is a lie by construction. Reject it, and be able to say why.
4. **`StatelessSession.insert()`** — never performs the new-vs-detached check at all. See §6, and note
   the batching trade-off in §7 before reaching for it.

### What it costs

On a one-Entry, N-Posting write with `save()`: N+1 SELECTs, none of which can be batched (they are
reads), each paying a round trip and a policy evaluation, all returning zero rows. With `persist()` and
batching on (§7), the same unit of work is one batched INSERT per table. That is the entire delta, and
it is available for the price of not using the convenience method.

---

## 6. `StatelessSession` inside a Spring-managed transaction

### It is a legitimate fit for this write path

The javadoc describes exactly this project's needs:

> A command-oriented API often used for performing bulk operations against the database. A stateless
> session has no persistence context, and always works directly with detached entity instances. When a
> method of this interface is called, any necessary interaction with the database happens immediately
> and synchronously.
>
> A stateless session comes with some designed-in limitations:
> - it does not have a first-level cache,
> - nor interact with any second-level cache,
> - nor does it implement transactional write-behind or automatic dirty checking.

([hibernate-orm 6.6, `StatelessSession.java`](https://github.com/hibernate/hibernate-orm/blob/6.6/hibernate-core/src/main/java/org/hibernate/StatelessSession.java))

No dirty checking is a feature on insert-only tables. No first-level cache is a feature under RLS, for
the same reason the second-level cache is off ([`rls-pooling.md` §2](rls-pooling.md)) — nothing outlives
the transaction that authorised reading it.

### The trap the RLS design creates

`SessionFactory` offers two ways in
([hibernate-orm 6.6, `SessionFactory.java`](https://github.com/hibernate/hibernate-orm/blob/6.6/hibernate-core/src/main/java/org/hibernate/SessionFactory.java)):

> `StatelessSession openStatelessSession()` — Open a new stateless session.
>
> `StatelessSession openStatelessSession(Connection connection)` — Open a new stateless session,
> utilizing the specified JDBC `Connection`. *@param connection Connection provided by the
> application.*

**The no-arg form is the bug.** It acquires its own connection from the pool. This project sets tenant
context with `set_config('app.current_tenant', ?, true)` — transaction-local, on *the transaction's*
connection. A second connection has no such setting, so under fail-closed policies its reads return
nothing and its inserts fail `WITH CHECK`. Worse, it is a *second* database transaction, so a rollback
of the Spring transaction does not undo its writes.

There is no ambiguity about this and no configuration that fixes it: **on Spring Boot 3.x there is no
`StatelessSession` transaction integration at all.** Spring Framework 7.0 is where it arrives —
`LocalSessionFactoryBean` *"exposes transactional `Session` and `StatelessSession` proxy references for
dependency injection, along the lines of the JPA 3.2 arrangement for `EntityManager` injection"*, with
Hibernate ORM 7.2 recommended over 7.1 because *"we can derive a transactional `StatelessSession` from
the current transactional `Session`"*
([Spring Framework 7.0 Release Notes](https://github.com/spring-projects/spring-framework/wiki/Spring-Framework-7.0-Release-Notes)).
Boot 3.x sits on Spring Framework 6.x. This project has to do it by hand.

### Doing it by hand, correctly

Hand it the transaction's own connection. `Session.doWork(…)` is the same hook this project already
uses to set the tenant GUC ([`rls-pooling.md` §2](rls-pooling.md)), which is exactly why it is the right
one here: it is provably the same connection.

```java
@Transactional
public void record(Entry entry, List<Posting> postings) {
    Session session = entityManager.unwrap(Session.class);
    session.doWork(connection -> {
        try (StatelessSession stateless = sessionFactory.openStatelessSession(connection)) {
            stateless.insert(entry);
            postings.forEach(stateless::insert);
        }
    });
}
```

One connection, one transaction, one `app.current_tenant`. Nothing borrows anything.

`DataSourceUtils.getConnection(dataSource)` is the other documented route — it *"Is aware of a
corresponding Connection bound to the current thread"*
([Spring, `DataSourceUtils`](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/jdbc/datasource/DataSourceUtils.html))
— but it depends on the transaction manager having exposed the connection to the JDBC binding.
`doWork` asks Hibernate directly and cannot be wrong.

### Sharp edges, all of them consequences of "no persistence context"

- **`close()` and the borrowed connection.** The javadoc says `close()` *"Close the stateless session
  and release the JDBC connection."* With an application-supplied connection Hibernate should release
  rather than close it, leaving Spring to commit — but the javadoc does not spell that out.
  **Unverified: write a test asserting the surrounding transaction still commits after the stateless
  session is closed.** If it does not, drop the try-with-resources and leave the connection entirely to
  Spring.
- **Exceptions do not mark the transaction rollback-only.** *"when an exception is thrown by a stateless
  session, the current transaction is not automatically marked for rollback."* A caught exception
  mid-Entry therefore leaves a half-written, unbalanced Entry heading for COMMIT. Let it propagate out
  of the `@Transactional` method; never catch it to "log and continue".
- **No cascade, no collections.** *"operations performed using a stateless session never cascade to
  associated instances"* and *"collections are ignored by a stateless session"*
  ([Hibernate 6.6 User Guide, Batching](https://docs.hibernate.org/orm/6.6/userguide/html_single/Hibernate_User_Guide.html#batch)).
  Insert the Entry and each Posting explicitly. For a ledger this is arguably an improvement: the write
  is written down.
- **Interceptors and event listeners do not fire** — *"operations performed via a stateless session
  bypass Hibernate's event model and interceptors"* — which retires any §4 in-JVM guard, and is a fourth
  reason the guard belongs in the database.
- **It does not batch.** See §7. This is the decisive trade-off.

**Position:** `StatelessSession` is defensible here and worth being able to discuss, but it is not free,
it is not integrated on Boot 3.x, and on a 1+N insert it may well be *slower* than a batched stateful
`persist()`. Reach for stateful `persist()` plus batching first (§7); keep the stateless path in reserve
for genuinely large ingest, where the persistence context itself is the problem.

---

## 7. Batching the Entry and its N Postings

### The settings

From the User Guide's batching chapter
([Hibernate 6.6 User Guide, JDBC batching](https://docs.hibernate.org/orm/6.6/userguide/html_single/Hibernate_User_Guide.html#batch-jdbcbatch)):

- **`hibernate.jdbc.batch_size`** — *"Controls the maximum number of statements Hibernate will batch
  together before asking the driver to execute the batch. Zero or a negative number disables this
  feature."* The guide's own advice elsewhere: *"set the `hibernate.jdbc.batch_size` property to an
  integer between 10 and 50."*
- **`hibernate.order_inserts`** — *"Forces Hibernate to order inserts to allow for more batching to be
  used. Comes with a performance hit, so benchmark before and after to see if this actually helps or
  hurts your application."*
- **`hibernate.order_updates`** — *"Forces Hibernate to order SQL updates by the entity type and the
  primary key value of the items being updated. This allows for more batching to be used. It will also
  result in fewer transaction deadlocks in highly concurrent systems."*
- **`hibernate.jdbc.batch_versioned_data`** — defaults to `true` since 5.0; turn it off only for drivers
  that return incorrect row counts. pgjdbc is not one of them.

Spring Boot passes native properties through the `spring.jpa.properties.*` prefix, which *"is stripped
before adding them to the entity manager"*
([Spring Boot reference, SQL databases](https://docs.spring.io/spring-boot/3.4/reference/data/sql.html)):

```properties
spring.jpa.properties.hibernate.jdbc.batch_size=50
spring.jpa.properties.hibernate.order_inserts=true
spring.jpa.properties.hibernate.order_updates=true
spring.jpa.open-in-view=false
```

`open-in-view=false` is not about batching — it is the RLS requirement from
[`rls-pooling.md` §2](rls-pooling.md), repeated here because it belongs in the same properties block and
must not get lost.

### What silently disables it

> **Hibernate disables insert batching at the JDBC level transparently if you use an identity identifier
> generator.**

([Hibernate 6.6 User Guide, Batching](https://docs.hibernate.org/orm/6.6/userguide/html_single/Hibernate_User_Guide.html#batch-session-batch))

and, on why:

> It is important to realize that using IDENTITY columns imposes a runtime behavior where the entity row
> **must** be physically inserted prior to the identifier value being known. […] Hibernate will not be
> able to batch INSERT statements for the entities using the IDENTITY generation.

([Hibernate 6.6 User Guide, IDENTITY columns](https://docs.hibernate.org/orm/6.6/userguide/html_single/Hibernate_User_Guide.html#identifiers-generators-identity))

**This schema is immune**, and that is worth saying out loud: because ids are UUIDs assigned in Java,
there is no `IDENTITY`, no `@GeneratedValue`, and nothing that forces a statement per row. The design
decision that costs a SELECT in §5 buys batching here. Never introduce a `BIGSERIAL` surrogate on a core
table without re-reading this paragraph.

The other silent disablers:

- `batch_size` unset or `<= 0` — and **it is unset by default**. Batching is opt-in; a Spring Boot app
  with no `batch_size` property batches nothing, and nothing tells you.
- **`StatelessSession`.** Its operations are *"always performed synchronously, resulting in immediate
  access to the database"* — one round trip per `insert()`, no batch. So §6 and §7 pull in opposite
  directions: the stateless session removes the persistence context and the batch together. For one
  Entry plus a handful of Postings, batched `persist()` wins.
- Interleaving entity types without `order_inserts`. Hibernate batches consecutive statements against
  the same table; a flush ordered Entry, Posting, Entry, Posting breaks the batch at every switch. With
  one Entry per transaction the natural order is already grouped and the setting earns little — turn it
  on when a request records several Entries, and benchmark, as the guide asks.
- A `flush()` inside the loop, which forces the batch out early.

### One extra turn from the driver

pgjdbc can collapse a JDBC batch into a single multi-row INSERT:

> `reWriteBatchedInserts` — This will change batch inserts from
> `insert into foo (col1, col2, col3) values (1, 2, 3)` into
> `insert into foo (col1, col2, col3) values (1, 2, 3), (4, 5, 6)` — this provides 2-3x performance
> improvement

Default `false`
([PostgreSQL JDBC: connection parameters](https://jdbc.postgresql.org/documentation/use/)). Note the
protocol ceiling of 65535 bind parameters, which caps rows per statement on wide tables; at a
`batch_size` of 50 that is not close.

This composes fine with RLS: the rewritten statement still runs on the same connection inside the same
transaction, so `app.current_tenant` and `WITH CHECK` apply per row exactly as before.

### Verifying it

Batching failures are invisible — the code works, it is just N times slower. Assert on it: enable
`hibernate.generate_statistics` and assert the statement count for a one-Entry, N-Posting write, or
count statements at the datasource in the integration test that already runs against real Postgres in
CI. A test that fails when someone adds a `@GeneratedValue(strategy = IDENTITY)` is the only thing that
will catch it.

---

## Where JPA is the wrong tool, and what is done instead

Stated plainly, because the project's position is that this friction gets handled deliberately rather
than hidden:

| Friction | Why JPA is a poor fit | Mitigation |
|---|---|---|
| Shared `tenant_id` across PK and FK | JPA has one owner per column; a column legitimately part of two mappings must be muted in one of them | `@JoinColumns(… insertable=false, updatable=false)` on the shared column; a factory that derives the child id from the parent so the two copies cannot diverge; the composite FK and RLS `WITH CHECK` as the actual guarantee |
| Insert-only tables | `@Immutable` ignores updates silently and says nothing about deletes; every guard it offers is in-JVM and bypassable | `@Immutable` kept as an optimisation and a declaration of intent; `REVOKE UPDATE, DELETE` from `app_user` as the barrier; per-command RLS policies as a second lever |
| Application-assigned UUID ids | Spring Data cannot distinguish new from detached, so `save()` degrades to `merge()` and a wasted SELECT per row | `EntityManager.persist()`, not `save()`; `Persistable` if repositories must stay `CrudRepository`-shaped; never `@Version` on an insert-only table |
| Transaction-local RLS context | `StatelessSession` — and anything else that borrows its own connection — silently escapes the tenant context | One connection per transaction, always; `openStatelessSession(connection)` via `doWork(…)` if a stateless session is used at all; `open-in-view=false`; second-level cache off |
| Constraints as the guarantee | Hibernate's schema validation checks tables and columns, not constraints | Flyway owns the schema; `ddl-auto=validate` only; a test per invariant that fails when the constraint is dropped |

The through-line: **every one of these resolves by moving the guarantee into the schema and letting
Hibernate be a mapping layer.** That is the project's thesis, and this is the evidence for it — the
ORM's weak spots are precisely the places where a constraint is already doing the work.

---

## Recommended pattern

1. **Ids:** `@EmbeddedId` with an `@Embeddable record … implements Serializable`. Pin Hibernate
   `>= 6.6.1.Final`.
2. **Composite FKs:** `@JoinColumns` with `referencedColumnName` on every column and
   `insertable=false, updatable=false` on the shared `tenant_id`. Derive the child id from the parent in
   a factory.
3. **Repositories:** derived queries always with the explicit `_` traversal separator
   (`findById_TenantId`). Writes go through `EntityManager.persist()`, not `CrudRepository.save()`.
4. **Insert-only:** `@Immutable` on the entities for the dirty-checking saving; `REVOKE UPDATE, DELETE`
   on `app_user` plus per-command RLS policies as the barrier; a test that asserts the DELETE is refused.
5. **Batching:** `batch_size=50`, `order_inserts=true`, `order_updates=true`,
   `reWriteBatchedInserts=true` on the JDBC URL, and a statement-count assertion in CI.
6. **`StatelessSession`:** only via `openStatelessSession(connection)` inside `session.doWork(…)`, only
   where the persistence context is measurably the problem, and knowing it forfeits batching.

Three things need a test in this repo rather than another document: whether `remove()` on an
`@Immutable` entity issues a DELETE; whether closing a `StatelessSession` opened on a Spring-managed
connection leaves the transaction committable; and whether an all-null read into a record embeddable
with a primitive component behaves.

---

## Sources

- [Hibernate 6.6 User Guide](https://docs.hibernate.org/orm/6.6/userguide/html_single/Hibernate_User_Guide.html) — composite identifier rules (public, no-arg constructor, serializable, equals/hashCode); `@EmbeddedId` vs `@IdClass`; `@ManyToOne` in an id class is not portable; derived identifiers and `@MapsId`; `@Immutable` "in the updateability sense", updates skipped with no UPDATE issued, immutable collections throw; custom `EmbeddableInstantiator` for records; `hibernate.jdbc.batch_size` / `order_inserts` / `order_updates` / `batch_versioned_data`; "Hibernate disables insert batching at the JDBC level transparently if you use an identity identifier generator"; `StatelessSession` limitations — no cascade, collections ignored, bypasses the event model and interceptors.
- [Hibernate 6.6 Introduction](https://docs.hibernate.org/orm/6.6/introduction/html_single/Hibernate_Introduction.html) — "This is not our preferred approach" / "we recommend that the `BookId` class be declared as an `@Embeddable` type"; `@Embeddable record BookId(…)` used as `@EmbeddedId`; "the easiest way to satisfy these requirements is to declare the id class as a `record`"; records as embeddables with the no-arg-constructor requirement relaxed; `persist()` schedules an insert with no immediate database effect, `merge()` copies onto a managed instance.
- [hibernate-orm 6.6 — `mapping/Value.java`](https://github.com/hibernate/hibernate-orm/blob/6.6/hibernate-core/src/main/java/org/hibernate/mapping/Value.java) — `checkColumnDuplication`, gated on `isColumnInsertable() || isColumnUpdateable()`, and the exact `MappingException` text.
- [hibernate-orm 6.6 — `annotations/Immutable.java`](https://github.com/hibernate/hibernate-orm/blob/6.6/hibernate-core/src/main/java/org/hibernate/annotations/Immutable.java) — "The changes are ignored, with no exception thrown"; `HibernateException` for collections; silent on delete.
- [hibernate-orm 6.6 — `StatelessSession.java`](https://github.com/hibernate/hibernate-orm/blob/6.6/hibernate-core/src/main/java/org/hibernate/StatelessSession.java) — no persistence context, no first- or second-level cache, no write-behind or dirty checking, no cascades; operations synchronous and immediate; `close()` releases the JDBC connection; exceptions do not mark the transaction for rollback.
- [hibernate-orm 6.6 — `SessionFactory.java`](https://github.com/hibernate/hibernate-orm/blob/6.6/hibernate-core/src/main/java/org/hibernate/SessionFactory.java) — `openStatelessSession()` vs `openStatelessSession(Connection)`.
- [hibernate-orm 6.6 — `PreDeleteEventListener.java`](https://github.com/hibernate/hibernate-orm/blob/6.6/hibernate-core/src/main/java/org/hibernate/event/spi/PreDeleteEventListener.java) — "Return true if the operation should be vetoed".
- [hibernate-orm 6.6 — `AbstractSchemaValidator.java`](https://github.com/hibernate/hibernate-orm/blob/6.6/hibernate-core/src/main/java/org/hibernate/tool/schema/internal/AbstractSchemaValidator.java) — validation covers tables, column types and sequences; no constraint checking.
- [hibernate-orm 6.6 — `changelog.txt`](https://github.com/hibernate/hibernate-orm/blob/6.6/changelog.txt) — HHH-16759 (6.3.0.Final), HHH-18062 (6.6.0.Final), HHH-18158 and HHH-18251 (6.6.0.CR1), HHH-18445 (6.6.1.Final).
- [Hibernate ORM 6.2 Final announcement](https://in.relation.to/2023/03/30/orm-62-final/) — "Hibernate now support the mapping of Java records as embeddables", with `@Embeddable` and `@Struct` examples.
- [Jakarta Persistence 3.1 — `@MapsId`](https://jakarta.ee/specifications/persistence/3.1/apidocs/jakarta.persistence/jakarta/persistence/mapsid) — designates a relationship attribute providing the mapping for an `EmbeddedId` primary key; the value element names the attribute within the composite key to which the relationship corresponds.
- [Jakarta Persistence 3.1 — `@JoinColumn`](https://jakarta.ee/specifications/persistence/3.1/apidocs/jakarta.persistence/jakarta/persistence/joincolumn) — `insertable` / `updatable` semantics and defaults; `referencedColumnName`; repeatable via `@JoinColumns`.
- [Jakarta Persistence 3.1 specification](https://jakarta.ee/specifications/persistence/3.1/jakarta-persistence-spec-3.1.html) — §2.4 primary key class requirements; §2.4.1 derived identities.
- [Spring Data JPA — Persisting Entities](https://docs.spring.io/spring-data/jpa/reference/jpa/entity-persistence.html) — `save()` calls `persist()` or `merge()`; entity state-detection strategies; "not an option for entities that use manually assigned identifiers and no version attribute"; the `Persistable` + transient-flag pattern.
- [Spring Data JPA — Property Expressions](https://docs.spring.io/spring-data/jpa/reference/repositories/query-methods-details.html) — the right-to-left camel-case split, its ambiguity, and `_` as the explicit traversal separator.
- [Spring Framework 7.0 Release Notes](https://github.com/spring-projects/spring-framework/wiki/Spring-Framework-7.0-Release-Notes) — `LocalSessionFactoryBean` exposes transactional `Session` and `StatelessSession` proxies; Hibernate ORM 7.2 recommended for `StatelessSession` support. Not available on Spring Boot 3.x.
- [Spring Framework — `DataSourceUtils`](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/jdbc/datasource/DataSourceUtils.html) — `getConnection` is aware of a `Connection` bound to the current thread; `releaseConnection` does not close a thread-bound connection.
- [Spring Boot reference — SQL databases](https://docs.spring.io/spring-boot/3.4/reference/data/sql.html) — `spring.jpa.properties.*` passes native Hibernate properties with the prefix stripped; `spring.jpa.open-in-view`.
- [PostgreSQL JDBC — connection parameters](https://jdbc.postgresql.org/documentation/use/) — `reWriteBatchedInserts`, default `false`, rewrites a batch into a multi-row `VALUES`.
- [PostgreSQL — Row Security Policies](https://www.postgresql.org/docs/current/ddl-rowsecurity.html) — per-command policies and default-deny when no policy matches.
