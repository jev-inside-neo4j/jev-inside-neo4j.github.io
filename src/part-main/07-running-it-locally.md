# Chapter 7: Running it locally

## Why run locally

Every decision so far has gone out over the internet. For some graphs that's fine. For others it isn't: the data may not be allowed to leave the building, the calls may add up to a cost you'd rather avoid or the machine may simply be offline.

A local model answers those objections. The data stay on your computer, no key is needed and nothing is sent anywhere. The price is what you'd expect: a small model on a laptop is not the same thing as a large hosted service and this chapter measures the difference instead of assuming it.

We use Ollama to serve the model. It exposes the same request format that Jev's API speaks, so the function we've built needs no new code. Everything in this chapter uses options we already have: `model` from chapter 2, `url` from chapter 5 and `concurrency` from chapter 6.

## Same function, new address

Pull the model once:

```shell
ollama pull tev1:0.8b
```

Then call `jev.decide` as before, with two options: the address of the local server and the name of the model:

```cypher
MATCH (t:Transaction {txn_id: 'T0001'})
RETURN jev.decide(
  t {.amount, .merchant_category, .distance_from_home_km, .hour_of_day,
     .account_age_days, .prior_flags, .txns_last_24h, .typical_monthly_spend},
  {decision: {
     type: 'choice',
     instructions: 'Based on all available signals, should this transaction be flagged for review or passed through?',
     criteria: {
       Flag: 'Potential fraud. Send for review.',
       Pass: 'Likely legitimate. Process normally.'
     }
  }},
  {url: 'http://localhost:11434/v1/systemone', model: 'tev1:0.8b'}
) AS result
```

This is what came back:

```
{
  answers: {
    decision: {
      type: "choice",
      choice: "Pass",
      probabilities: {
        Pass: 0.7310585786300049,
        Flag: 0.2689414213699951
      },
      confidence: 0.16005846201683083
    }
  },
  error_message: null,
  metadata: {
    authenticated: false,
    model: "tev1:0.8b",
    latency_ms: 859,
    attempts: 1
  }
}
```

The shape is the one we know. The choice is `Pass`, the probabilities add up to 1 and the metadata names the model that answered.

Look at `authenticated`. It's false, which is the rule from chapter 5 at work. The key is sent only to Jev, so a call to your own machine goes out without it. You don't need a key on a machine that only ever talks to a local model and a missing key is not an error here.

One new unit test checks that a response from a local server, with extra fields in it, is accepted. That brings the suite to 68 tests.

## Same shape, different meaning

The result looks the same, but one field means something else. In chapter 2 we saw that Jev's `confidence` is the margin between the two probabilities. The local server's `confidence` is one minus the normalized entropy of the probabilities, a different measure.

For the answer above, the probabilities are 0.731 and 0.269. The margin between them is 0.462. The reported confidence is 0.160. Both are reasonable numbers for the same answer and neither is wrong. They just aren't the same thing, so a Cypher query that compares confidence across the two sources would be comparing different quantities.

The notebook tests this directly. For the one transaction it uses, it checks that the reported value is one minus the normalized entropy and that it is not the margin:

| Test | Status | Detail |
|---|---|---|
| local model: confidence is one minus normalized entropy | PASS | |
| local model: confidence is NOT the margin that Jev reports | PASS | reported 0.0112, margin 0.1244 |

The fix is to compute the margin yourself from the probabilities, which both sources provide. In Cypher it's one expression:

```cypher
abs(d.p_flag - d.p_pass) AS margin
```

The local decisions we store later in this chapter carry a `margin` property for exactly this reason.

There's one smaller difference. A `score` question comes back from the local model as an integer, such as `1`. The notebook records the type as `int`. If you do arithmetic on these scores alongside decimal ones, use `toFloat()` first.

## Cold and warm

The first call to a local model is slow, because the server has to load the model into memory. Later calls find it already loaded. The notebook measures both by asking the server to unload the model, making one call and then ten more.

The cold call took 881 milliseconds. The ten warm calls took between 21 and 31 milliseconds, with a median of 22. Inside the function the cold call took 867 milliseconds and the warm ones between 16 and 19. The median overhead of the trip over Bolt and the Cypher planning was 6 milliseconds.

So a warm local call, measured this way, is far faster than a hosted call, which took around 235 milliseconds in chapter 4. But keep reading, because a warm call on a repeated request turns out not to be the whole story.

## Failures are still data

Everything from chapter 3 carries over, because the local server goes through the same `JevClient`. Two cases show it.

Ask for a model that hasn't been pulled and the server answers with a 404. The function reports it as a client error and doesn't retry, because retrying a missing model can't help:

```
http_4xx: 404 {"error":"model \"does-not-exist\" not found, try pulling it first"}
```

The notebook confirms the call used one attempt. Run the same request as a batch and every row reports the same error and the batch still finishes.

If the server isn't running at all, the connection is refused. The error names the host and the port, `localhost:11434`, so you can tell at once that it's the local server that's missing and not the network. It's an `io` error, so it's retried with the usual backoff before the function gives up.

## Repeatable, in steps

Run the same call three times and the hosted service may answer with slightly different probabilities each time, as we saw in chapters 5 and 6. The local model doesn't. In the notebook, three identical calls gave exactly one distinct result and a row from `jev.decideAll` was identical to the same state sent through `jev.decide`.

Repeatable doesn't mean fine-grained. Across all 500 local decisions in the full run below, `p_flag` took only 15 distinct values. Ten of them covered 429 of the 500 decisions and the three most common, 0.2451, 0.2227 and 0.3208, covered 193. Every one of the 15 is the logistic function of a multiple of 0.125. For example, 0.2689 is 1/(1+e^1), 0.2227 is 1/(1+e^1.25) and 0.5 is 1/(1+e^0). We don't know why the model answers in steps like these and we haven't tried to find out. What matters in practice is that many transactions get exactly the same probability, so there's less room to rank them by how sure the model was.

## The full run, locally

Now the same 500 transactions. The statement is the one from chapter 6, with two changes: the options point at the local server and the answers are stored as `LocalDecision` nodes, linked by `HAS_LOCAL_DECISION`. That leaves the hosted `Decision` nodes untouched, so the two sets can be compared:

```cypher
MATCH (t:Transaction)
WHERE NOT (t)-[:HAS_LOCAL_DECISION]->()
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
  {url: 'http://localhost:11434/v1/systemone', model: 'tev1:0.8b', concurrency: 4}
) YIELD id, answers, error_message, metadata
WITH id, answers, metadata, error_message
WHERE error_message IS NULL
MATCH (t:Transaction {txn_id: id})
MERGE (t)-[:HAS_LOCAL_DECISION]->(l:LocalDecision)
SET l.choice     = answers.decision.choice,
    l.confidence = answers.decision.confidence,
    l.p_flag     = answers.decision.probabilities.Flag,
    l.p_pass     = answers.decision.probabilities.Pass,
    l.margin     = abs(answers.decision.probabilities.Flag - answers.decision.probabilities.Pass),
    l.latency_ms = metadata.latency_ms,
    l.attempts   = metadata.attempts,
    l.model      = metadata.model
RETURN count(l) AS local_decisions_stored
```

The notebook reported:

```
Changes: nodes_created 500, relationships_created 500, properties_set 4000
Completed after 64,262 ms
```

Five hundred local decisions, all stored, in about 64 seconds at a concurrency of 4. The hosted run took 14.2 seconds at a concurrency of 8. These two aren't a like-for-like comparison, since the concurrency differs and we'll come back to what the local server does with parallel calls.

The function's own latency averaged 504 milliseconds per call locally and 223 hosted. The local calls ranged from 83 to 1,488 milliseconds, with a median of 451.

## Hosted against local

The queries now have two sets of decisions to compare. First, what the local model decided, set against the hidden label:

| Local decision | Not fraud | Fraud |
|---|---|---|
| Flag | 5 | 16 |
| Pass | 306 | 173 |

The local model flagged 21 transactions and passed 479. Of the 189 transactions labeled as fraud, it flagged 16 and passed 173. For comparison, the hosted run in chapter 6 flagged 170 of those 189 and passed 19.

Then the two sets against each other. This query joins each transaction's hosted decision to its local one and counts the combinations:

```cypher
MATCH (t:Transaction)-[:HAS_DECISION]->(h:Decision),
      (t)-[:HAS_LOCAL_DECISION]->(l:LocalDecision)
RETURN h.choice AS hosted, l.choice AS local, count(*) AS n
ORDER BY hosted, local
```

| Hosted | Local | Transactions |
|---|---|---|
| Flag | Flag | 21 |
| Flag | Pass | 247 |
| Pass | Pass | 232 |

They agree on 253 of the 500 and the local model never flagged anything the hosted service passed. The 247 disagreements aren't confined to the ambiguous cases. By scenario:

| Scenario | Disagreements |
|---|---|
| card_testing | 64 |
| clear_fraud | 54 |
| night_atm | 52 |
| traveler | 47 |
| takeover | 29 |
| big_purchase | 1 |

We ran the full local run once for this chapter, so we can't say how much its totals move from one run to the next. The hosted counts moved a little between runs, so treat a count at the level of one or two transactions with care.

The scenario list includes `clear_fraud`, where we'd expect any judge to flag. This small model, with the same prompt and the same eight properties, is simply far more willing to pass. When it flags, it's usually right (16 of its 21 flags were fraud), but it flags very few.

That doesn't make the local model useless. It means that with this prompt it's a conservative filter and anyone using it would want to look at its confidence and the instructions again before trusting it with the same job. We haven't tried other prompts or other models, so we can't say whether a different setup would close the gap.

The confidence scales show the earlier point in numbers. Averaged over the 500 transactions, the hosted service's reported confidence was 0.694 and its margin, computed from the probabilities, was also 0.694, because for Jev they're the same thing. The local model's reported confidence averaged 0.121, but its margin averaged 0.355. The reported numbers differ by a factor of almost six and the margins by a factor of two. Only the margin puts them on one scale.

The statements from this chapter are also in `notebooks/07_run_local_500.ipynb`, which runs them one per cell and shows each result. The last one deletes the local decisions and is switched off by default.

## Concurrency locally

In chapter 6 the pool gave a large speedup, because the hosted service could answer many requests at once. Across the hosted sweeps we ran, the speedup ranged from about 5.4 to 7.0 times at 8 workers and from about 7.4 to 10.8 times at 16. A model on one laptop is a different matter, since all the calls share the same hardware.

The notebook's local sweep times a batch of 40 items at five levels of concurrency, ten times each, with a warm-up batch at every level first. The medians:

| Concurrency | Median (ms) | Speedup |
|---|---|---|
| 1 | 523 | 1.0x |
| 2 | 326 | 1.6x |
| 4 | 186 | 2.81x |
| 8 | 242 | 2.16x |
| 16 | 344 | 1.52x |

There were no errors at any level. Two workers gave about 1.6 times the speed and four gave the best result, about 2.8 times. At 8 the median was slower than at 4 and at 16 it was slower again. That's not the hosted result, where the speedup kept growing. A local model can run only so many calls at once and extra workers just queue behind each other. Pairing each repeat with the same repeat at a concurrency of 1 gives the same shape: 1.67, 2.67, 1.87 and 1.55 at 2, 4, 8 and 16. An earlier sweep had 2 and 4 level, so the gain from 2 to 4 is the least certain part. The gains stop at around 4 workers, which is what we used for the full run.

The individual timings show something else, which we can't yet explain. At concurrency 4 the ten runs of the same batch fell into two groups. Seven took 167 to 197 milliseconds and three took 335 to 347. At concurrency 2 the split was the other way round: two runs took 169 and 180 and eight took 306 to 355. At 8 five runs took 177 to 208, two took 275 and 281 and three took 351 to 354. At 16 three took 220 to 258 and seven took 303 to 365. The slow group is roughly twice the fast one and we haven't found a pattern in which runs landed in which group. At a concurrency of 1 every run took between 320 and 622 milliseconds, with no fast group.

We don't know why. It could be the model server, the machine or the way the calls overlap and we haven't separated these. We report it because it affects how far to trust a single timing and because the same batch taking either of two speeds is the kind of thing that would ruin a benchmark that ran each setting once.

There's a bigger caveat on the sweep itself. It sends the same 40 requests again and again, with only four properties in each state. At 4 workers the median batch of 40 took 186 milliseconds, about 4.7 milliseconds per item. The full run sent 500 different transactions, each with eight properties and took about 129 milliseconds per item at the same concurrency. That's about 27 times slower per item. We haven't tested whether repetition, the size of the state or something else explains it. The sweep shows how the speedup changes with concurrency. It doesn't tell you what a real run costs and for that you need to run the real thing, as we did above.

## What you'd hit in production

**Check the model is pulled before a batch.** A missing model fails every row with the same 404. It fails fast and clearly, but you still lose the run.

**Time the real run, not a repeated request.** A request that repeats is much cheaper than a stream of new ones. Warm calls of 21 to 31 milliseconds describe the best case and not what your graph will cost.

**Don't expect more workers to help a local model.** More workers kept helping hosted calls, but a local model gained little beyond the fourth. Measure on your own hardware before raising it.

**Don't compare reported confidence across the two sources.** Compute a margin from the probabilities. That's what the `margin` property on `LocalDecision` is for.

**A model and a prompt are a pair.** Our local model flagged very little with the same prompt that made the hosted service flag more than half. Treat a change of model like a change of judge and test it against labels you trust before using its decisions.

**Local doesn't mean unmanaged.** The model server runs on your machine, uses its memory and can be stopped or updated by you. If it isn't running, calls fail with an `io` error that names it.

## Where we've got to

We started with a graph that couldn't answer a question about judgment. We now have a function that makes one decision, a procedure that makes thousands, errors that come back as data, tests that need no network, a key that can only go to one place and a choice of where the model runs. Each lesson came from something that went wrong or something we measured and each is in the code and the numbers in this book.
