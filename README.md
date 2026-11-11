# mini-nu

[![CI](https://github.com/guipiacsek/mini-nu/actions/workflows/ci.yml/badge.svg)](https://github.com/guipiacsek/mini-nu/actions/workflows/ci.yml)

A small bank ledger in Clojure and Datomic. Money can't be created, lost or spent twice, it moves between USD and BRL at a recorded rate, and you can read any account's balance on any past date.

I built it to learn how Nubank builds software: Clojure, immutable data and Datomic, with [Schema](https://github.com/plumatic/schema) for request validation and [Component](https://github.com/stuartsierra/component) for startup and shutdown.

**[How it works, and why →](docs/DESIGN.md)**: architecture, the life of a transfer, the data model, and the trade-offs.

## Features

- **Double-entry ledger** in USD and BRL; every currency always sums to zero.
- **Cross-currency transfers.** The rate used is stored on the transfer, and changing the rate never rewrites the past.
- **Idempotent transfers.** A retry with the same `Idempotency-Key` never moves money twice.
- **No overdraft under load.** The balance check runs inside the database transaction.
- **Night limits.** Customers can send less between 20:00 and 06:00 local time, like Brazil's Pix. The limit is a total per period, enforced inside the transaction.
- **Credit cards** with a credit limit, purchases, and a monthly bill read from Datomic's history.
- **Reversals.** A transfer is undone by adding its exact opposite; nothing is ever deleted.
- **Scheduled transfers** (every minute, day, week or month) that can't pay twice, even after a crash.
- **Audit trail.** Every write records who, from where and with which request id, on the transaction itself.
- **Statements** with date filters and pagination.
- **Time travel.** `?as-of=` returns any past balance.
- **Persistent storage.** Data survives restarts.
- **Metrics:** request counts, money-movement outcomes and latency percentiles.
- **Demo UI** at `/`: plain HTML and JS that drives the real API.

## The problem

A bank ledger has jobs that are easy to get wrong:

1. **Never lose or invent money.** Every transfer must take exactly as much as it gives, even across currencies.
2. **Never move money twice.** Mobile networks drop connections, so clients retry, and schedulers restart.
3. **Never overdraw under load.** Two transfers at the same moment must not both pass the balance check.
4. **Never rewrite history.** Regulators and customers need to know what happened, when, at which rate, and who asked.

## Design decisions

| Decision | Why |
|---|---|
| **Double entry.** A transfer writes entries that sum to zero per currency. A USD→BRL transfer writes four: the customer's USD out, the bank's USD in, the bank's BRL out, the receiver's BRL in. | Money is conserved by construction, even across currencies. Deposits are transfers from the bank's account, so there is no special case. |
| **The balance is never stored**, only calculated from the entries. | No stored balance that can drift away from the history. |
| **Append-only.** Datomic never overwrites, and a reversal adds the opposite entries instead of deleting. | `?as-of=` comes for free, and the full history is always auditable. |
| **The FX rate is stored on each transfer.** Reversals use the original amounts, not today's rate. | A rate change can never alter what already happened. |
| **Idempotency key per transfer** (unique in the database). Scheduled runs use `schedule-id + run time` as the key. | A retried request, or a scheduler that crashes mid-run, can't pay twice. |
| **Checks run inside Datomic transaction functions** (`transfer-tx`, `reverse-tx`). | Datomic runs transactions one at a time, so a check and its write can't be interleaved. No locks, no retry loops. The balance, credit and night-limit checks all work this way. |
| **A credit card is an account allowed below zero**, down to its limit. Its closed bill is its balance `as-of` the closing time. | Purchases and payments are ordinary transfers with the same checks, and the bill needs no extra bookkeeping. |
| **Schemas check the shape of a request; the ledger checks the rules.** | A malformed request is a 400 that names each bad field; a well-formed one that breaks a rule is a 422. |
| **Audit data is attached to the transaction entity.** | The who/where/request id is stored atomically with the data it explains, so it can't be missing. |
| **Pure rules live in `ledger.clj`.** | The rules that matter most are the easiest to test. |
| **Amounts are integer cents, and conversion uses banker's rounding.** | No floating-point errors, and no systematic rounding bias. |

## Run it

**Just Java (no Clojure needed):** download `mini-nu.jar` from the [latest release](https://github.com/guipiacsek/mini-nu/releases/latest), then:

```bash
java -jar mini-nu.jar
```

**From source** (Java 11+ and the [Clojure CLI](https://clojure.org/guides/install_clojure)):

```bash
clojure -M:test   # run the tests
clojure -M:run    # http://localhost:3000, data stored in ./data
clojure -T:build uber   # build target/mini-nu.jar yourself
```

CI runs the tests and builds the jar on every push. Pushing a tag like `v1.0.0` also publishes a release with the jar attached.

| Setting | Default | |
|---|---|---|
| `PORT` | `3000` | |
| `MINI_NU_DATA` | `data` | Directory for the database. `mem` keeps everything in memory. |

Open http://localhost:3000 and click **Create demo accounts** to get Gui (USD) and Mom (BRL).

## API

| Method & path | What it does |
|---|---|
| `GET /accounts` · `POST /accounts {owner, currency}` | List or open accounts (`USD` or `BRL`) |
| `GET /accounts/:id/balance?as-of=<ISO instant>` | Current or past balance |
| `GET /accounts/:id/statement?from=&to=&limit=&cursor=` | Newest first; pass `next-cursor` for the next page |
| `POST /deposits {to, amount}` | Needs an `Idempotency-Key` header |
| `POST /transfers {from, to, amount}` | Needs an `Idempotency-Key` header; converts the currency if needed |
| `GET /accounts/:id/limit` | The day or night limit in force, what's used, and when it resets |
| `POST /accounts/:id/card {credit-limit, closing-day}` | Opens a credit card paid from this account (`closing-day` 1–28) |
| `POST /cards/:id/purchases {amount, merchant}` · `POST /cards/:id/payments {amount}` | Need an `Idempotency-Key`; payments come from the linked account |
| `GET /cards/:id/bill` | Owed, available credit, the last closed bill and this cycle's spending |
| `GET /transfers/:id` | The transfer plus its audit record |
| `POST /transfers/:id/reversal` | Reverse once; calling it again returns the same reversal |
| `GET /fx-rates` · `PUT /fx-rates {from, to, rate}` | Sets the rate and its inverse |
| `POST /schedules {from, to, amount, every, start-at?}` | `every` is `minute`, `day`, `week` or `month` |
| `GET /accounts/:id/schedules` · `DELETE /schedules/:id` | List or cancel schedules |
| `GET /metrics` | Request counts, outcomes, latency p50/p95/p99, ledger totals |

Amounts are integer cents in the sender's currency. Optional headers: `X-Actor` (who is asking, stored in the audit trail) and `X-Request-Id` (generated and returned when missing).

```bash
curl -s -H 'content-type: application/json' -H 'Idempotency-Key: tr-1' \
  -d '{"from":"<gui>","to":"<mom>","amount":2500}' localhost:3000/transfers
# => {"amount":2500,"credited":13500,"fx-rate":5.4,"from-currency":"USD","to-currency":"BRL",...}
```

| Response | When |
|---|---|
| `201` | Done, or a retry of something already done |
| `400` | Missing `Idempotency-Key`, or a malformed field (`details` names each one) |
| `404` | Unknown account, transfer or schedule |
| `409` | `idempotency-conflict` (same key, different data) or `card-exists` |
| `422` | Breaks a rule: `insufficient-funds`, `over-limit`, `card-not-allowed`, `invalid-amount`, `same-account`, `cannot-reverse-reversal`, ... |

## Tests

```bash
clojure -M:test   # 22 tests, 125 assertions
```

- **Property-based** (test.check): random transfers across USD and BRL accounts keep every currency at zero and no customer balance negative.
- **Concurrency:** 100 parallel transfers from one account never overdraw.
- **Idempotency:** retries, conflicting retries, and a scheduler "crash" between paying and moving the schedule on.
- **FX:** the stored rate, a rate change not touching old transfers, and a reversal using the original amounts.
- **Reversals:** reversing once only, no reversing a reversal, and no clawing back money the receiver already spent.
- **Schedules:** due/not-due runs, failed runs recorded, cancelling.
- **Night limits:** day/night periods across time zones and daylight saving, a period's total, and 40 concurrent transfers that can't jointly pass the limit.
- **Cards:** the credit limit, cards refused for transfers, a closed bill read with `as-of`, payments and refunds.
- **System:** the whole app starts on a random port and releases it when stopped.
- **Audit, paging, date filters, persistence across connections, and the HTTP layer end to end.**

Assertions use [matcher-combinators](https://github.com/nubank/matcher-combinators).

## Layout

```
src/mini_nu/
  schema.clj    Datomic schema
  ledger.clj    pure rules: validation, double entry, FX, reversal, limits, bill cycles, schedule dates
  db.clj        queries, transaction functions, writes, the scheduler
  http.clj      routes with Schema validation, audit and metrics middleware
  system.clj    Component system: database, scheduler, web server; main entry point
  metrics.clj   in-process counters
resources/public/
  index.html    demo page (plain HTML + JS, no build step)
docs/DESIGN.md  architecture, diagrams and trade-offs
test/mini_nu/   ledger, db, features, cards/limits, http and system tests
```

## What I'd do next

- Export metrics in Prometheus format, so they survive restarts and work across several instances.
- Authentication, with the audit actor taken from the verified identity instead of a header.
- Pull live FX quotes with an expiry time instead of a manually set rate.
- Move statement filtering into the database index for accounts with millions of entries.
- Limits and card terms per customer, stored with history like FX rates, instead of constants.
- Interest and minimum payments on card bills.
