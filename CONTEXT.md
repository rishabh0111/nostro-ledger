# Nostro

A multitenant double-entry ledger, exposed as an API. Tenants record movements of money; the system's
job is to make it structurally impossible to record one that does not balance, to lose one, to apply
one twice, or to let one tenant's money touch another's.

## Language

**Tenant**:
An isolated owner of ledger data. No Account, Entry or Posting is ever visible or referenceable
across a Tenant boundary.
_Avoid_: Organisation, customer, workspace, client

**Account**:
A named place within a Tenant against which value accumulates, denominated in a single Currency.
An Account is created explicitly, and its Currency never changes.
_Avoid_: Wallet, ledger, bucket, pot

**Constrained Account**:
An Account declared, at creation, never to hold a negative Balance. The declaration never changes.
Every other Account is unconstrained and may hold any Balance.
_Avoid_: Asset account, real account, funded account

**Posting**:
One leg of an Entry: a signed Amount applied to exactly one Account. A Posting never exists on its
own — it is only ever part of an Entry.
_Avoid_: Line, line item, leg, movement, split

**Entry**:
The whole balanced fact: a set of Postings, recorded together, whose Amounts sum to zero within each
Currency they touch. An Entry is immutable once recorded; a mistake is corrected by recording a
reversing Entry, never by amending the original.
_Avoid_: Transaction, transfer, journal entry, record, event

**Amount**:
An exact quantity of one Currency, carried in that Currency's smallest unit. Amounts in different
Currencies never sum together.
_Avoid_: Value, sum, figure, decimal

**Reversing Entry**:
An ordinary Entry that undoes an earlier one, referencing the Entry it reverses. An Entry can be
reversed at most once. A Reversing Entry is subject to every rule an Entry is subject to, including
a Constrained Account's floor — so a correction can be refused.
_Avoid_: Correction, adjustment, void, refund, cancellation

**Idempotency Key**:
A caller-supplied identifier, unique within a Tenant, under which an Entry is recorded at most once.
Presenting the same key again returns what the first attempt returned.
_Avoid_: Request id, dedup key, reference, client reference

**Balance**:
The net of every Posting against an Account in a given Currency.
_Avoid_: Total, sum, amount

**Position**:
A comparable marker of how much of a Tenant's history a Balance reflects. Recording an Entry yields
the Position it created; every Balance is reported with the Position it reflects. Opaque: callers
compare Positions by presenting them, never by reading them.
_Avoid_: Version, offset, sequence, timestamp, watermark, cursor

**Currency**:
The unit an Account is denominated in, with a fixed smallest unit. An Entry must balance within each
Currency it touches, separately. The ledger never converts between Currencies and never holds a
rate: an exchange is expressed by the caller as Postings against their own Accounts.
_Avoid_: Denomination, unit, asset

## Words this domain does not use

**"Transaction" belongs to the database and nothing else.** It means a unit of atomic work — the
thing `@Transactional` opens, that Tenant context is scoped to, and that Postgres reports on. A
movement of money is an **Entry**.

**There is no "Transfer".** A movement of money has no lifecycle and nothing to observe midway: it
is recorded or it is refused. Where the word is tempting, the concept is an Entry.

**There are no holds or reservations.** A Balance reflects what has been recorded, and nothing else
is set aside against it.
