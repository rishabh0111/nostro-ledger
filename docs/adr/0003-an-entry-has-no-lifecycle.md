# An Entry has no lifecycle

There is no `Transfer` type, no status on an Entry, and no holds or reservations. A caller POSTs an
Entry; it is recorded or it is refused, synchronously, and the answer is known before the response
is written.

A lifecycle earns its place only when something can legitimately be *pending* — awaiting an external
settlement, an approval, or an asynchronous execution. This ledger validates against its own Postgres
inside one transaction: the Entry balances or it does not, the floor holds or it does not. A state
machine whose every instance goes `pending → posted` within a single transaction is ceremony.

## Considered options

A status column on the Entry was rejected outright: a mutable status on an immutable fact is a
contradiction, and it invites an `UPDATE` onto a table whose entire value is that it never takes one.

Holds and reservations were considered seriously and rejected on cost, not on principle. They are a
genuine two-phase concept and would pair naturally with constrained Accounts, but they roughly
double the balance model (available versus posted), and they add expiry and therefore a scheduler.
If they are ever wanted, they should displace scope rather than be added to it.
