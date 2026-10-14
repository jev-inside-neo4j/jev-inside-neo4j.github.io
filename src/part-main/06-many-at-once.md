# Chapter 6: Many at Once

## One at a Time

Everything so far has been one decision per call. That's fine for a single transaction, but our graph has 500 and a real one could have millions.

The problem is in how a function call works. `jev.decide` sends a request and waits for the answer and the whole query waits with it. A query that calls it for every node makes its calls one after another. From the chapter 4 notebook, a hosted call takes about a quarter of a second, so 500 of them in a row would take about two minutes. That's an estimate from the arithmetic, but we'll see later in this chapter that it holds up.

Most of that time is waiting. The database sits idle while Jev thinks. If we could have several requests in flight at once, the waiting would overlap. This chapter adds the piece that does it.

## A Procedure, Not a Function

A function returns one value. What we want to return is a stream of rows, one per input, so the new piece is a procedure, `jev.decideAll`. Its shape is:

```cypher
CALL jev.decideAll(items, questions [, options])
YIELD id, answers, error_message, metadata
```

The three arguments are:

- **`items`** is a list of maps. Each has a `state`, which can be text, a map or a list as for `jev.decide` and usually an `id`. Without an `id`, the item's position (0, 1, 2 and so on) is used.
- **`questions`** is one map of questions, shared by every item.
- **`options`** is optional. It takes the same options as `jev.decide`, plus one new one: `concurrency`.

If you misspell an option, the error lists the options `jev.decide` accepts (`connect_timeout_ms`, `max_retries`, `model`, `request_timeout_ms` and `url`) and leaves out `concurrency`, which only `jev.decideAll` accepts. The list is incomplete, not the option.

Each row it returns has the same `answers`, `error_message` and `metadata` as a single call, plus the `id` that was passed in. That lets us match each answer to its node.

Like `jev.decide`, the procedure is a thin wrapper. The class Neo4j sees, `JevDecideAll`, hands everything to `BatchRunner`, which holds the logic. `BatchRunner` takes the same `JevClient` we've been building, so every guarantee from the earlier chapters still applies to each row: validation first, retries with backoff, failures as data and the key rule.

## A Bounded Pool

`BatchRunner` runs the calls on a pool of worker threads. The number of threads is the `concurrency` option, from 1 to 16 and it defaults to 4:

```java
int threads = Math.min(concurrency, items.size());
ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
    Thread thread = new Thread(runnable, "jev-decide-" + THREAD_COUNTER.incrementAndGet());
    thread.setDaemon(true);
    return thread;
});
```

The pool is bounded for two reasons. The service has rate limits and a thousand simultaneous requests is a good way to meet them. And the database is serving other work, so a batch shouldn't be able to take every thread it can find. The cap of 16 and the default of 4 are choices, not laws of nature and you can tune them for your own setup.

The threads are daemons and have names, `jev-decide-1`, `jev-decide-2` and so on, so they're easy to spot in a thread dump and can't keep the database from shutting down.

One rule matters more than the others: the workers receive only plain values. They get a state, the questions and the options. They never touch the Neo4j transaction. The transaction belongs to the thread that called the procedure and handing it to other threads is a way to produce failures that are very hard to diagnose.

## Order and Failures

The calls finish in any order, but the rows come back in the order the items were given. `BatchRunner` submits every item to the pool and then reads the results back by position:

```java
for (int i = 0; i < items.size(); i++) {
    Object id = idOf(items.get(i), i);
    ...
    rows.add(futures.get(i).get());
}
```

Reading the first result waits for the first call, reading the second waits for the second and so on. A fast call that finished early simply waits its turn in the list. The caller sees the same order as the input.

A failing row doesn't stop the others. Each row carries its own `error_message`, exactly as a single `jev.decide` call does. Beyond the failures `JevClient` already handles, the batch adds a few of its own:

- An item without a `state`, or a `null` item, becomes a row with a `validation:` error. The rest run.
- A bad `concurrency` value gives every row a `validation:` error and makes no calls at all.
- A batch holds at most 10,000 items. One more is the exception to the failures-as-data rule: the whole call fails with an error that says so, and no rows come back. Larger sets go in several calls.
- An unexpected exception in a worker becomes an `internal:` error on its row.

If the thread that called the procedure is interrupted, for instance when someone cancels the query, the pool is shut down at once and the unfinished rows come back as `interrupted:` errors. The thread's interrupt flag is set again afterwards, so Neo4j can see the cancellation.

## Testing the Batch

The 12 new unit tests in `BatchRunnerTest` use the fake transport from chapter 4. They check the ordering, the bound on concurrency, the default of four, the per-row failures, the limits and the interrupt. All 67 tests in the project pass and the batch tests took about half a second, longer than the rest because they run real threads.

The notebook, `06_decideall_tests.ipynb`, checks the same things against the real procedure. A closed port is enough for most of them, because every call fails and still has to land in the right row. Here are some of the results:

| Test | Status | Detail |
|---|---|---|
| order: one row per item | PASS | 20 rows |
| order: ids come back in input order | PASS | |
| concurrency 0 is rejected on every row | PASS | validation: concurrency must be between 1 and 16 |
| concurrency 'two' is rejected on every row | PASS | validation: concurrency must be a whole number |
| per-row: a missing state fails only that row | PASS | validation: item 0 needs a state |
| per-row: the next item still ran | PASS | io: ConnectException reaching 127.0.0.1:9 (after 1 attempt) |
| ids: a missing id falls back to the position | PASS | [0, 1] |
| limit: an empty list gives no rows | PASS | 0 rows |

With the hosted section switched on, the notebook recorded 20 passes and 2 measurements. The measurements come in the timing section below.

## The Full Run

Now the whole graph. This statement picks every transaction without a decision, builds the list of items, calls `jev.decideAll` with a concurrency of 8 and stores each answer as a `Decision` node:

```cypher
MATCH (t:Transaction)
WHERE NOT (t)-[:HAS_DECISION]->()
WITH collect({id: t.txn_id,
              state: t {.amount, .merchant_category, .distance_from_home_km, .hour_of_day,
                        .account_age_days, .prior_flags, .txns_last_24h, .typical_monthly_spend}}) AS items
CALL jev.decideAll(
  items,
  {decision: {
     type: 'choice',
     instructions: 'Based on all available signals, should this transaction be flagged for review or passed through?',
     criteria: {
       Flag: 'Potential fraud. Send for review.',
       Pass: 'Likely legitimate. Process normally.'
     }
  }},
  {concurrency: 8}
) YIELD id, answers, error_message, metadata
WITH id, answers, metadata, error_message
WHERE error_message IS NULL
MATCH (t:Transaction {txn_id: id})
MERGE (t)-[:HAS_DECISION]->(d:Decision)
SET d.choice     = answers.decision.choice,
    d.confidence = answers.decision.confidence,
    d.p_flag     = answers.decision.probabilities.Flag,
    d.p_pass     = answers.decision.probabilities.Pass,
    d.latency_ms = metadata.latency_ms,
    d.attempts   = metadata.attempts,
    d.model      = metadata.model
RETURN count(d) AS decisions_stored
```

The map projection is the same one we've used since chapter 2, so `scenario`, `true_p` and `is_fraud` never reach Jev. Three details are worth noting:

- **Failed rows are left out.** The `WHERE error_message IS NULL` skips them, so a failure never produces a half-empty `Decision`.
- **The statement can be run again.** Its first line only picks up transactions that have no decision, so after a partial failure, running it a second time fills the gaps and leaves the rest alone. `MERGE` on the relationship means it never creates a second decision for the same transaction.
- **It's one call.** The whole set of 500 goes to the procedure at once. The rows come back when every call has finished.

The notebook reported:

```
Changes: nodes_created 500, relationships_created 500, properties_set 3500
Completed after 14,172 ms
```

That's all 500 decisions stored in one pass and every result below comes from this run. It doesn't always go that smoothly. In an earlier run, one row came back with an error and was left out, which is what the `WHERE error_message IS NULL` line is for. We don't know what the error was, because the statement doesn't keep it. A count of the decisions showed 499 of 500 and, as the second detail above says, running the same statement again picked up the one transaction without a decision. It stored it in 446 milliseconds.

So 500 hosted decisions took about 14.2 seconds, around 28 milliseconds each. The timings section below measures about 274 milliseconds for a call made on its own, which puts 500 calls in a row at about 137 seconds. That's almost ten times longer than the run took, which is more than eight workers should manage if every call takes the same time. The calls in this run were simply faster: 14.2 seconds on 8 workers for 500 calls works out at about 227 milliseconds each. Call times vary from run to run, so treat the speedup as one measurement.

The `Decision` node settles the properties we left open in chapter 1:

| Property | What it holds |
|---|---|
| `choice` | `Flag` or `Pass` |
| `confidence` | The margin between the two probabilities |
| `p_flag` | The probability Jev gave to Flag |
| `p_pass` | The probability Jev gave to Pass |
| `latency_ms` | How long the call took, measured inside the function |
| `attempts` | How many attempts it needed |
| `model` | Which model answered |

## What the Decisions Say

With the decisions stored, the questions from chapter 1 are queries. First, how the 500 split:

```cypher
MATCH (:Transaction)-[:HAS_DECISION]->(d:Decision)
RETURN d.choice AS choice, count(*) AS n
ORDER BY n DESC
```

Jev flagged 268 transactions and passed 232. The query we promised in chapter 1 finds the flagged transactions it was least sure about:

```cypher
MATCH (t:Transaction)-[:HAS_DECISION]->(d:Decision)
WHERE d.choice = 'Flag'
RETURN t.txn_id, t.amount, d.p_flag
ORDER BY d.p_flag ASC
LIMIT 5
```

```
╒════════╤════════╤════════╕
│t.txn_id│t.amount│d.p_flag│
╞════════╪════════╪════════╡
│"T0114" │170.87  │0.5     │
├────────┼────────┼────────┤
│"T0173" │5.39    │0.52    │
├────────┼────────┼────────┤
│"T0308" │60.59   │0.52    │
├────────┼────────┼────────┤
│"T0058" │733.15  │0.52    │
├────────┼────────┼────────┤
│"T0130" │308.49  │0.52    │
└────────┴────────┴────────┘
```

These are the closest to a coin flip and a reviewer should look there first. Now we can use the hidden label, which Jev never saw:

```cypher
MATCH (t:Transaction)-[:HAS_DECISION]->(d:Decision)
RETURN d.choice AS choice, t.is_fraud AS is_fraud, count(*) AS n
ORDER BY choice, is_fraud
```

| Decision | Not fraud | Fraud |
|---|---|---|
| Flag | 98 | 170 |
| Pass | 213 | 19 |

Of the 189 transactions labeled as fraud, 170 were flagged and 19 were passed. Of the 268 flagged, 98 were legitimate. Those 19 are the cases to worry about, so a query can list them, least sure first:

```cypher
MATCH (t:Transaction)-[:HAS_DECISION]->(d:Decision)
WHERE d.choice = 'Pass' AND t.is_fraud = 1
RETURN t.txn_id, t.scenario, d.p_flag
ORDER BY d.p_flag DESC
LIMIT 5
```

```
╒════════╤══════════════╤════════╕
│t.txn_id│t.scenario    │d.p_flag│
╞════════╪══════════════╪════════╡
│"T0445" │"night_atm"   │0.49    │
├────────┼──────────────┼────────┤
│"T0443" │"traveler"    │0.48    │
├────────┼──────────────┼────────┤
│"T0180" │"big_purchase"│0.37    │
├────────┼──────────────┼────────┤
│"T0089" │"night_atm"   │0.32    │
├────────┼──────────────┼────────┤
│"T0357" │"big_purchase"│0.28    │
└────────┴──────────────┴────────┘
```

The scenario column shows where the misses come from. Four of the five are late-night cash withdrawals or big purchases, the kinds of transaction that chapter 1 described as genuinely ambiguous. The fifth, T0443, is a traveler, and Jev gave it almost even odds. Remember that the labels were drawn from a latent probability, so some "fraud" here is fraud that no judge could be sure of.

The last of the questions from chapter 1 is how much of the graph could be handled without a person looking at it. That depends on a threshold and any number we pick is an illustration:

```cypher
MATCH (:Transaction)-[:HAS_DECISION]->(d:Decision)
RETURN count(*) AS decisions,
       sum(CASE WHEN d.confidence >= 0.8 THEN 1 ELSE 0 END) AS confident,
       round(100.0 * sum(CASE WHEN d.confidence >= 0.8 THEN 1 ELSE 0 END) / count(*), 1) AS percent_confident
```

With a confidence of 0.8 as the line, 246 of the 500 decisions, or 49.2%, were confident.

## The Same Run Four Times

We ran the whole batch several times. Each run's decisions were deleted before the next and everything above comes from the last one. Here are the last four side by side:

| | Run 1 | Run 2 | Run 3 | Run 4 |
|---|---|---|---|---|
| Flagged | 268 | 271 | 267 | 268 |
| Passed | 232 | 229 | 233 | 232 |
| Flagged, not fraud | 97 | 100 | 97 | 98 |
| Passed, fraud | 18 | 18 | 19 | 19 |
| Confident (0.8 or more) | 254 | 248 | 256 | 246 |

To run the batch again from scratch, delete only the decisions and leave the transactions alone:

```cypher
MATCH (d:Decision) DETACH DELETE d
```

The full-run statement only picks up transactions that have no decision, so without this step a second run would find nothing to do.

The statements from this chapter, plus two checks, are also in `notebooks/06_run_all_500.ipynb`, which runs them one per cell and shows each result. The reset is the last one and is switched off by default. If the decisions are already stored, statement 2 stores 0, because it only picks up transactions that have no decision yet.

The overall picture is stable and the details move. A transaction at the edge can land on either side and the five least-sure flags were mostly different transactions each time (T0289, T0316 and T0359 led the first list, T0267, T0445 and T0175 the second, T0307, T0378 and T0065 the third and T0114, T0173 and T0308 the fourth). Only T0017 turned up on two of the lists, the second and the third. All of them sat between 0.50 and 0.52. The transactions near the line move around and the ones far from it don't.

That has a practical consequence. If you store decisions, treat them as a snapshot from one run of a service that can answer slightly differently the next time. The `model` and `latency_ms` properties on each `Decision` help you tell runs apart later.

## What the Timings Show

The notebook's hosted sweep times a batch of 20 items at four levels of concurrency, twice each. The batches are taken in rotation so a slow stretch on the network doesn't land on one level.

| Concurrency | Run 1 (ms) | Run 2 (ms) | Median (ms) | Speedup |
|---|---|---|---|---|
| 1 | 5,491 | 5,456 | 5,473 | 1.0x |
| 4 | 1,421 | 1,402 | 1,412 | 3.88x |
| 8 | 804 | 755 | 780 | 7.02x |
| 16 | 539 | 476 | 507 | 10.79x |

There were no errors at any level. At a concurrency of 1, the 20 calls took about 274 milliseconds each, which gives us the number behind the estimate at the start of the chapter. The speedup is close to the concurrency at 4 and a little under it at 8, so most of the time really was waiting.

The results at 8 and 16 need a note. Twenty items on 16 workers still need two rounds, because the last four calls have to wait for workers to free up. If every call took the same time, the best possible speedup would be 10 times at 16 workers and about 6.7 times at 8 workers, which needs three rounds of 8, 8 and 4. The measured 10.79x and 7.02x are both above those limits, so the calls didn't all take the same time. The one-at-a-time run was probably slower per call than the others, but we didn't measure by how much. An earlier sweep gave 6.85x at 8 and 7.4x at 16, so these numbers move between runs and the limits are a guide, not a promise. Workers beyond the number of items buy nothing, because the pool never starts more threads than there are items.

Back to the full run: the sweep's roughly 7 times at a concurrency of 8 is below the almost 10 times we worked out for the full run, which is another sign that call times move between runs. The same statement took 15,076 milliseconds in an earlier run, close to the 14,172 above. We didn't time the full run enough to call it a benchmark, so treat it as a measurement and not a promise.

## What You'd Hit in Production

**More concurrency only helps if the service can answer in parallel.** A hosted API usually can, as the sweep shows. Chapter 7 puts a local model behind the same procedure and the picture is different.

**Retries aren't coordinated.** A 429 on one row is retried with the same backoff as in chapter 3, but the rows don't share a throttle. At a high concurrency a rate-limited service can see many retries at once.

**Rows arrive when everything is done.** The procedure returns once every call has finished, so a large batch against a slow endpoint takes that long before the first row appears. Keep batches to a size you're happy to wait for.

**Batches have a ceiling.** At 10,000 items per call, a bigger graph goes in slices. Add `WITH t LIMIT 10000` after the `WHERE` in the full run, before the `collect`, and the "no decision yet" filter does the rest, because each pass picks up where the last stopped.

**Decisions are data you now own.** The stored `Decision` nodes don't change when the service does. If you want fresh ones, delete the old `Decision` nodes first, as we did between runs.

## Going Further

We've gone from one decision to a stored, queryable judgment on every transaction, with timings that show the pool earning its place. In the last chapter we swap the service behind the same function. The hosted model gives way to a small one running on your own machine and we'll see what stays the same, what changes and what that does to the numbers.
