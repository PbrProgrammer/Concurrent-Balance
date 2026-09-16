# Concurrent Balance Service

In-memory Spring Boot service for crediting, debiting, and transferring account balances under concurrent load.

The design is intentionally small: one service, one mutable `Account` type, and per-account locks. There is no database, message broker, or global service lock.

## Architecture

```text
src/main/java/com/balance
├── BalanceApplication.java
├── domain
│   ├── Account.java              # balance + per-account lock
│   ├── Transaction.java          # immutable processed-transaction record
│   ├── OperationType.java
│   └── exceptions/               # domain errors
└── service
    ├── BalanceService.java       # public API
    └── BalanceServiceImpl.java   # in-memory concurrent implementation
```

| Component | Responsibility |
| --- | --- |
| `BalanceService` | The operation API: create account, credit, debit, transfer, get balance. |
| `BalanceServiceImpl` | Validates requests, acquires the right account locks, applies idempotency, mutates balances, and records successful transactions. |
| `Account` | Holds `accountId`, `balance`, and the `ReentrantLock` that guards that balance. |
| `Transaction` | Immutable record of a successfully applied `transactionId`. Used only for idempotency. |
| Domain exceptions | Typed failures for unknown accounts, invalid amounts, insufficient funds, same-account transfer, and bad input. |

Accounts are stored in a `ConcurrentHashMap<String, Account>`. Processed transactions are stored in a `ConcurrentHashMap<String, Transaction>`.

`createAccount` is an extra API method. The challenge operations cannot run unless accounts exist, and unknown accounts must fail rather than be auto-created.

## Concurrency

Thread safety comes from **account-level `ReentrantLock`s**, not from synchronizing the service.

- `credit` / `debit` / `getBalance` lock only the target account.
- `transfer` locks **both** accounts, always in the same order.
- The processed-transaction map is a concurrent map, but the idempotency check and the balance mutation happen **while the relevant account lock(s) are held**.

That is the reason a global `synchronized` method was rejected: it would serialize every account in the process. Independent accounts can proceed in parallel.

What happens in practice:

| Scenario | Behavior |
| --- | --- |
| Two operations on account `A` | They queue on `A`'s lock. Each check-and-mutate runs atomically. |
| Operation on `A` and operation on `B` | Different locks. They run concurrently. |
| `transfer(A, B)` and `transfer(B, A)` | Both need `A` and `B`, acquired in sorted `accountId` order, so they serialize without deadlock. |
| `getBalance(A)` during `transfer(A, B)` | `getBalance` takes `A`'s lock. Transfer already holds both locks before mutating, so a reader never observes a partial transfer. |

`ReentrantLock` also gives a happens-before edge: a balance write under the lock is visible to the next thread that acquires that lock.

## Idempotency

Every credit, debit, and transfer has a `transactionId`.

- A **successful** operation stores that id in `processedTransactions` before releasing the account lock(s).
- A retry with the same id returns immediately and does **not** change balances. This is not a business failure; retries are expected.
- A **failed** operation (for example insufficient funds) does **not** record the id. A later retry may succeed if the account is funded. The id is reserved for an effect that actually happened.

Duplicate detection is not a bare `if (!set.contains(id))` outside a lock. For a single-account operation the sequence is:

```text
lock(account)
  if transaction already processed → return
  mutate balance
  record transaction
unlock(account)
```

Concurrent duplicates of `credit(A, 100, "TX-1")` all contend for `A`'s lock. Only the first thread mutates and records. The rest see the recorded id and return. The balance increases by 100, not by `100 × threadCount`.

The same pattern holds for transfer, except both account locks are held across the check, both mutations, and the record.

`transactionId` is assumed unique per intended financial effect. Reusing the same id for a *different* operation is a client error and is not modeled as a separate conflict type.

## Transfer

Transfer is one logical operation:

```text
validate amount, ids, and same-account rule
load source and destination (fail if either is missing)
lock min(accountId), then lock max(accountId)
  if transaction already processed → return
  debit source (fails the whole operation if funds are insufficient)
  credit destination
  record transaction
unlock in reverse order
```

If debit fails, destination is not credited and the transaction is not recorded. There is no window where money has left the source but not arrived at the destination, including for concurrent `getBalance` calls.

### Deadlock prevention

Locks are acquired by lexicographic `accountId` order:

```text
first  = min(sourceId, destinationId)
second = max(sourceId, destinationId)
```

`transfer(A, B)` and `transfer(B, A)` both lock `A` then `B` (assuming `A < B`). They cannot wait on each other in opposite order.

### Same-account transfer

`transfer("A", "A", 100, "TX-100")` is rejected with `SameAccountTransferException`.

This is treated as invalid input, not as a no-op. A self-transfer does not change wealth and is almost always a client bug. The check runs before account lookup, so it also fails when the account does not exist.

## Failure Semantics

| Situation | Result | Balances | `transactionId` |
| --- | --- | --- | --- |
| Unknown account | `AccountNotFoundException` | Unchanged | Not recorded |
| `amount <= 0` | `InvalidAmountException` | Unchanged | Not recorded |
| Blank/null id | `InvalidRequestException` | Unchanged | Not recorded |
| Debit/transfer with insufficient funds | `InsufficientBalanceException` | Unchanged | Not recorded (retry may later succeed) |
| Same-account transfer | `SameAccountTransferException` | Unchanged | Not recorded |
| Duplicate of a **successful** transaction | Silent success (no-op) | Unchanged by the retry | Already recorded |
| Duplicate account creation | `AccountAlreadyExistsException` | Unchanged | n/a |

Failed transfers do not leave a partial update. The destination is credited only after the source debit succeeds, and both writes happen under both locks.

## Storage

Everything is in memory in a single JVM process:

- `ConcurrentHashMap` for account lookup and for processed transaction ids
- one `ReentrantLock` per `Account`

That is enough for correctness of this challenge. It is not a distributed ledger.

## Trade-offs

Intentionally kept simple:

- No REST API, database, Kafka, Redis, or Docker
- No global lock, actor framework, or STM
- No retry/outbox layer
- Domain objects stay few: `Account`, `Transaction`, exceptions, one service

Limitations of the in-memory implementation:

- State is lost on process restart
- Processed `transactionId`s grow without bound (no TTL / compaction)
- One process only: two application instances would each have their own maps and **would not** be consistent with each other
- No durability, replication, or crash recovery
- `long` balances use `Math.addExact` on credit to fail on overflow rather than wrap, but there is no decimal/currency type

What would change with a database:

- Replace the maps with a transactional store
- Make `transactionId` a unique constraint / idempotency key in the same DB transaction as the balance update
- Run transfer as a single ACID transaction (or equivalent compare-and-set) so check + debit + credit + insert cannot be partially committed
- Row-level locks, or `UPDATE ... WHERE balance >= amount`, would take the place of `ReentrantLock`
- `getBalance` would read committed state rather than taking a process-local lock

What would change in a multi-instance production system:

- In-memory locks do not work across JVMs
- Idempotency and balances must live in shared durable storage
- You would still need a unique `transactionId` and atomic apply-or-ignore semantics
- Distributed locking alone is usually the wrong primary tool; the database (or a consensus store) should be the source of truth

## Tests

Run:

```bash
JAVA_HOME=/path/to/jdk-21 mvn test
```

Coverage:

- Basic credit, debit, transfer, get balance
- Unknown account, invalid amount, insufficient funds, same-account transfer
- Sequential idempotency for credit, debit, and transfer
- Failed operations do not consume `transactionId`
- 100 concurrent duplicate credits/debits/transfers of the same id (effect applied once)
- 100 concurrent unique debits against 1000 (exactly 10 succeed, balance 0, never negative)
- Concurrent credits/debits on independent accounts A, B, and C
- Concurrent `A→B` and `B→A` transfers: no deadlock, no negative balance, total money conserved
- Cyclic transfers among A, B, and C: total money conserved
- Concurrent start is coordinated with `CyclicBarrier`; completion uses `ExecutorService.invokeAll` with a timeout so a deadlock fails the test instead of hanging
