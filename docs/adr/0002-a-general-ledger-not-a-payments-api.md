# A general ledger, not a payments API

The API accepts an Entry of N Postings and enforces that it balances within each Currency it
touches. Tenants define their own Accounts and their own meaning for them. There are no opinionated
account types, no parties, and no payment concepts.

The central claim of this project — that it is structurally impossible to record a movement that
does not balance — is a general-ledger claim. Building a payments or wallet API instead would hide
that invariant behind a product, and would add a surface of product decisions a reader can disagree
with, none of which is the point. A general ledger is also the smaller thing to build.

## Consequences

The one thing a payments API would have bought — genuine contention on a hot account with a balance
floor — is preserved by letting an Account be declared **constrained** at creation (see ADR-0004).
"Insufficient funds" is therefore a property of one Account, not a feature of a product.

Explicitly out of scope, and deliberately so: a hierarchical chart of accounts with rolled-up
balances; exchange rates (an Entry may span Currencies, but the caller supplies the exchange
Postings and the ledger never invents a rate); and reporting beyond a single Account's history.
