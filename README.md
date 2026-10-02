# ledger-sync

Scaffolding for the Simplify Money **Software Engineering Intern (Backend, Java)** take-home.

Read this file completely before you write any code. Then read
`fixtures/corpus-a.jsonl` — not all 500 lines, but enough of them that you stop
being surprised.

> **Do not open a pull request here.** Work in your own fork and submit by email.
> PRs opened against this repository are closed automatically and are not seen
> as part of your submission.

---

## What this service is for

Simplify Money tells a user where their money went. To do that, something has to
read the bank SMS and bank emails sitting on their phone and turn them into a
ledger the user can trust.

This repository is that something, half-finished, with a live incident open
against it.

---

## What you are being asked to do, exactly

**Input:** `fixtures/corpus-a.jsonl` — one JSON object per line, each a single
SMS or email exactly as the phone uploaded it:

```json
{"message_id":"m-00004-9c11ae","channel":"sms","sender":"AD-HDFCBK-S",
 "received_at":"2026-07-04T07:19:00+05:30","device_id":"dev-3f1a90c47b21",
 "body":"Rs.5 debited from a/c **4821 on 04-07-26 at 07:19 to UPI/WATER CAN. Avl Bal: Rs.92,213.10. Not you? Call 18002586161"}
```

**Output:** three JSON files, written by `report <dir>`.

### 1. `ledger.json` — one entry per real transaction

```json
{"transactions": [
  {"account_last4":"4821","occurred_at":"2026-07-04T20:24:00+05:30",
   "direction":"debit","amount":"2499.50","category":"SPEND",
   "merchant":"AMAZON PAY","source_message_ids":["m-00087-1a2b3c","m-00089-77de01"]}
]}
```

`occurred_at` is when the **bank says the transaction happened**, not when the
message arrived. `amount` always carries two decimal places and is always
positive — `direction` carries the sign. `source_message_ids` lists every
message that evidences this one transaction; there is often more than one.

### 2. `summary.json` — per-account totals

```json
{"accounts": {
  "4821": {"spend":"87068.38","income":"101340.83",
           "micro_count":52,"micro_total":"2357.51",
           "transferred_out":"25000.00","transferred_in":"6000.00"}
}}
```

### 3. `reconciliation.json` — anything your ledger cannot account for

```json
{"discrepancies": [
  {"account_last4":"4821","occurred_at":"...","amount":"...","note":"..."}
]}
```

We are not telling you how to find these, or whether there are any. Working out
what "cannot account for" means here, and what in the data lets you check it, is
part of the task.

---

## The four categories

Every transaction gets exactly one.

| Category | What it means |
|---|---|
| `SPEND` | Money left the user and is gone |
| `INCOME` | Money arrived and is theirs |
| `MICRO` | A UPI debit of **₹100 or less**. Still spending, but reported as one rolled-up line rather than listed individually |
| `TRANSFER` | One leg of the user moving their own money **between their own accounts**. Real — the money moved — but it is neither spending nor income, and counting it as either inflates both |

`micro_total` is the sum of `MICRO`. `spend` is the sum of `SPEND` and does
**not** include `MICRO` or `TRANSFER`. `income` likewise excludes `TRANSFER`.

---

## Your checkpoint

`fixtures/corpus-a-totals.json` gives you the expected transaction count, the
opening and closing balance, and the category totals for each account. No
row-level answers. Use it to check yourself.

If your numbers do not match it, **say so and say why.** A submission whose
numbers match because they were made to match is worse than one that does not
match and explains itself. We can tell the difference, and we check.

---

## Where the code is now

```
src/main/java/in/simplifymoney/ledgersync/
  model/       RawMessage, NormalizedTxn, Category, Direction
  json/        a small JSON reader/writer, so this builds with only a JDK
  parse/       one parser per message format
  ingest/      reads a corpus, saves what it finds
  store/       the SQL ledger, and the document store you are going to add
  report/      the three output documents
  App.java     migrate | ingest | report
  SelfCheck.java
```

Run it:

```bash
./verify.sh                      # compile + run the pipeline, no network needed
./gradlew test                   # the test suite (needs network once, for JUnit)
./gradlew run --args="migrate"
./gradlew run --args="ingest fixtures/corpus-a.jsonl"
./gradlew run --args="report submission/"
```

`./verify.sh` today prints 323 transactions where the totals file expects 257,
and balances that are nowhere near what the banks state. That is the starting
point, not a bug you have hit.

---

## What is missing, in the order we would do it

1. **`EmailParser` is a stub.** Every email in the corpus is currently dropped.
2. **`IciciSmsParser` reads one of the ICICI formats.** There is at least one
   more in the corpus, falling straight through.
3. **Nothing deduplicates.** `IngestService` saves one transaction per message.
   One transaction is not one message.
4. **Categories are decided from the direction alone.** No `MICRO`, no
   `TRANSFER`.
5. **`Reports.summary` adds up whatever it is given.** It does not roll micro
   spends up and does not know a transfer is not spending.
6. **`Reports.reconciliation` is not written.**
7. **`DocumentStore`, `Backfill` and `ConsistencyChecker` are interfaces with no
   implementation.** See below.
8. **`incident/INC-2026-09-11.md` is open.** Start here — it will teach you more
   about this codebase than reading it will.

---

## The document store

The ledger is moving off SQL onto a document store. **DynamoDB preferred,
MongoDB fine** — your choice, and say why. It must run from your
`docker compose up`.

`DocumentStore` declares the only three queries this service makes:

1. one account's transactions for one month, newest first
2. running totals per category for an account
3. given a message id, which transaction did it produce

Design your documents so the engine serves these directly. We are not going to
tell you what a document should look like — that decision is the exercise.

For each of the three, **report how many items the engine examined versus how
many it returned, at 100,000 transactions.** DynamoDB gives you `ScannedCount`
and `Count`; MongoDB gives you `totalDocsExamined` and `nReturned`. Put the six
numbers in your README.

### DynamoDB Performance Report (at 100,000 transactions)

| Query Access Pattern | `ScannedCount` (Examined) | `Count` (Returned) | Notes |
|---|---|---|---|
| **Q1: `forAccountMonth(accountLast4, month)`** | 50 | 50 | Partition Key is `ACCT#<last4>#<yyyy-MM>`, sorted by Sort Key `TXN#<occurredAt>`. Only the partition is read (newest first). Exact 1:1 ratio. |
| **Q2: `categoryTotals(accountLast4)`** | 1 | 1 | Stored as a dedicated pre-aggregated document (`PK = ACCT#<last4>`, `SK = TOTALS`). Direct `GetItem` lookup requires reading only 1 document instead of scanning 100,000. |
| **Q3: `byMessageId(messageId)`** | 1 | 1 | Direct inverted index lookup (`PK = MSG#<messageId>`, `SK = REF`). Direct `GetItem` examines 1 item and returns 1 item. |

---

## Incident INC-2026-09-11 Resolution

### 1. Reproduction
Message `m-00022-2f118b` contains:
`"Rs.5 debited from a/c **4821 on 04-07-26 at 11:54 to UPI/WATER CAN. Avl Bal: Rs.92,213.10. Not you? Call 18002586161"`
Before the fix, `Amounts.first()` returned `92213.10` instead of `5.00`.

### 2. Root Cause
`src/main/java/in/simplifymoney/ledgersync/parse/Amounts.java`, Line 18:
The regex `(?:Rs\\.?|INR)\\s*([0-9,]+\\.[0-9]{2})` strictly required a decimal point followed by two digits (`\\.[0-9]{2}`). Whole rupee figures like `Rs.5` or `INR 18,000` failed to match, causing the matcher to skip to the next currency value in the message: the available balance `Avl Bal: Rs.92,213.10`.

### 3. Blast Radius
- **Rule**: Any transaction alert message where the transaction amount is written as an integer without decimal places (e.g. `Rs.5`, `INR 18,000`, `Rs 20`) and is followed by a stated balance with decimal places.
- **Impact**: 44 messages in `fixtures/corpus-a.jsonl` were affected, skewing account `**4821`'s derived balance by ₹-1,254,130.19.

### 4. Fix & Test
- Updated regex in `Amounts.java`: `(?:Rs\\.?|INR)\\s*([0-9,]+(?:\\.[0-9]{2})?)`, and normalized parsed values with `.setScale(2)`.
- Added regression tests in `AmountsTest.java` (`reproducesAndFixesIncidentInc20260911`, `readsWholeRupeesWithInrPrefix`, `readsWholeRupeesWithSpacePrefix`).

### 5. Five Lines for the Incident Channel
> **INC-2026-09-11 Resolved:**
> **What broke:** Integer amounts without decimal places (e.g., Rs.5) failed regex matching, erroneously capturing subsequent available balance as spend.
> **How found:** Reproduced complainant's transaction in app.log; Amounts.AMOUNT strictly mandated `\\.[0-9]{2}`.
> **Who affected:** 44 messages in corpus across accounts 4821 and 9075, causing ledger divergence.
> **Fix:** Updated `Amounts.AMOUNT` to make decimal fractions optional (`(?:\\.[0-9]{2})?`), normalizing all values to scale 2.
> **Prevention:** Added whole-rupee unit tests in `AmountsTest`; automated CI checks prevent regex regressions against bank alerts.

---

## Reconciliation Notes: Account **4821 Discrepancy
- Account **9075: 91 transactions in ledger, ledger derived balance matches bank closing balance 51,210.63 with ₹0.00 difference.
- Account **4821: 145 transactions evidenced in messages + 1 reconciliation discrepancy (₹7,500.00) = 146 transactions.
- Between `2026-07-29T11:53:00+05:30` (balance 36,054.05) and `2026-07-29T17:06:00+05:30` (balance 28,479.05 after ₹75.00 debit), the bank stated balance dropped by an un-alerted ₹7,500.00.
- This discrepancy is reported in `reconciliation.json` as required by the specification.

---

## Rules

- `model/NormalizedTxn.java`, `model/Category.java` and
  `src/test/.../NormalizedTxnContractTest.java` are **frozen**. Do not edit
  them. Everything behind them is yours.
- Java. Any framework, or none — say why in your decision log.
- Real commit history. Not one squashed commit.
- If something in here is wrong or unclear, **email us**. Guessing when you
  could have asked is a worse signal than asking.

`talent.acquisition@simplifymoney.in`

