# mini-nu

A tiny bank ledger in Clojure and Datomic. Money can't be created, lost or spent twice, and you can ask for any account's balance on any past date.

I built it to learn how Nubank builds software: Clojure, immutable data and Datomic.

## The problem

A bank ledger has three jobs that are easy to get wrong:

1. **Never lose or invent money.** Every transfer must take exactly as much from one account as it gives to another.
2. **Never move money twice.** Mobile networks drop connections, so clients retry. A retried transfer must not debit the customer again.
3. **Never overdraw under load.** Two transfers from the same account at the same moment must not both pass the balance check.

## Design decisions

| Decision | Why |
|---|---|
| **Double-entry ledger.** A transfer writes two entries, a debit and a credit, that sum to zero. | Money is conserved by construction. Deposits are transfers from an `external` bank account, so there is no special case. |
| **The balance is never stored**, only calculated as the sum of entries. | No stored balance that can drift away from the history. |
| **Entries are append-only** (Datomic never overwrites). | `GET /balance?as-of=` comes for free from `d/as-of`: the past is still there. |
| **Idempotency key per transfer**, stored with a uniqueness constraint. | A retry returns the original transfer. The same key with different data is rejected with 409. |
| **The balance check runs inside a Datomic transaction function** (`mini-nu.db/transfer-tx`). | Datomic runs transactions one at a time, so the check and the write cannot be interleaved. No locks, no retry loops. |
| **Pure rules live in `ledger.clj`**, away from the database. | The rules that matter most are the easiest to test. |
| **Amounts are integer cents.** | No floating-point rounding with money. |

## Run it

Requires Java 11+ and the [Clojure CLI](https://clojure.org/guides/install_clojure).

```bash
clojure -M:test   # run the tests
clojure -M:run    # API on http://localhost:3000 (in-memory: data resets on restart)
```

## API

```bash
# open two accounts
curl -s -H 'content-type: application/json' -d '{"owner":"Gui"}' localhost:3000/accounts
# => {"id":"<A>"}

# deposit $100.00 (amounts in cents)
curl -s -H 'content-type: application/json' -H 'Idempotency-Key: dep-1' \
  -d '{"to":"<A>","amount":10000}' localhost:3000/deposits

# transfer $25.00; send it again with the same key and nothing moves twice
curl -s -H 'content-type: application/json' -H 'Idempotency-Key: tr-1' \
  -d '{"from":"<A>","to":"<B>","amount":2500}' localhost:3000/transfers

curl -s localhost:3000/accounts/<A>/balance
curl -s 'localhost:3000/accounts/<A>/balance?as-of=2026-11-10T02:00:00Z'
curl -s localhost:3000/accounts/<A>/statement
```

| Response | When |
|---|---|
| `201` | Transfer done, or a retry of one that was done |
| `400` | Missing `Idempotency-Key`, or a malformed field |
| `404` `unknown-account` | An account doesn't exist |
| `409` `idempotency-conflict` | The same key was sent with different data |
| `422` `insufficient-funds` / `invalid-amount` / `same-account` | The transfer breaks a rule |

## Tests

- **Property-based** (test.check): random sequences of transfers between accounts never change the total money, never leave a customer balance negative, and the bank's external account always mirrors the customers' total.
- **Idempotency:** a retry returns the same transfer and debits once, and a reused key with different data is refused.
- **Concurrency:** 100 parallel $0.10 transfers from an account holding $5.00 result in exactly 50 successes and a balance of zero.
- **Time travel:** the balance before a transfer is still readable after it.

Assertions use [matcher-combinators](https://github.com/nubank/matcher-combinators).

## Layout

```
src/mini_nu/
  schema.clj   Datomic schema
  ledger.clj   pure rules: validation, double entry
  db.clj       queries, the transaction function, transfer!
  http.clj     reitit routes
test/mini_nu/
  ledger_test.clj
  db_test.clj
```

## What I'd do next

- Durable storage (Datomic Local on disk) instead of in-memory.
- Multi-currency BRL/USD transfers, with the exchange rate stored as an immutable fact on the transfer.
- Expire idempotency keys after a retention window.
- Pagination for statements.
