# The domain does not say "transaction"

"Transaction" is the database's word — the unit of atomic work that `@Transactional` opens, that
tenant context is scoped to, and that Postgres reports on — and this design leans on it constantly.
It is also the obvious English word for a movement of money, which would make sentences like "the
transaction is recorded in one transaction" simultaneously true and useless. The domain surrenders
the word: a movement of money is an **Entry**, composed of **Postings**, against **Accounts**.

## Considered options

Making the *database* surrender it — calling the unit of work a "unit of work" in prose and code —
was rejected because the word cannot actually be taken back. It is `@Transactional`,
`PlatformTransactionManager`, `TransactionSynchronization`, `BEGIN`, and every Postgres error
message and stack trace a reader will ever see. Renaming it in prose while the framework says
`Transaction` is a fiction that breaks on first contact. The domain's word is the one we genuinely
control, and double-entry bookkeeping supplies a precise replacement at no cost.

## Consequences

The accounting literature is not consistent about "entry", so this repository is: an **Entry** is the
whole balanced fact, a **Posting** is one leg of it. "Entry" is never used for a leg.
