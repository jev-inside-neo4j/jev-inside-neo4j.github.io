# Chapter 3: Failures as Data

## The Problem with Throwing

The function from chapter 2 works as long as everything goes right. Here's what happens when something doesn't. Take four transactions, one of which doesn't exist and ask for a decision on each:

```cypher
UNWIND ['T0001', 'T0002', 'BAD', 'T0003'] AS id
OPTIONAL MATCH (t:Transaction {txn_id: id})
RETURN id, jev.decide(
  t {.amount, .merchant_category, .distance_from_home_km, .hour_of_day,
     .account_age_days, .prior_flags, .txns_last_24h, .typical_monthly_spend},
  {decision: {
     type: 'choice',
     instructions: 'Based on all available signals, should this transaction be flagged for review or passed through?',
     criteria: {Flag: 'Potential fraud. Send for review.', Pass: 'Likely legitimate. Process normally.'}
  }}
) AS result
```

The third row has no transaction behind it, so there's no real state to decide about. With the chapter 2 version, a failure like that raises an error and the whole query fails. You get no answers at all, not even for the transactions that were fine. Scale this to 500 nodes and one bad property, one slow response or one dropped connection throws away everything the query has already paid for.

The cause is a design choice. In Cypher, an error in a function stops the statement. That's right for a built-in like `toInteger`, where a bad value is a bug in your query. It's wrong for a call to a remote service, where failure is a normal event.

## The Rule

From this chapter on, `jev.decide` never throws. Every failure comes back in the result:

```
{
  answers: null,
  error_message: "validation: state must not be null",
  metadata: { model: "jev-latest", latency_ms: 0, attempts: 0 }
}
```

The map has the same three parts as before. When the call works, `answers` is filled and `error_message` is null. When it doesn't, `answers` is null and `error_message` says why. Either way the query carries on.

That turns failure into data and data are what Cypher is good at. You can count the failures, group them, filter them out or run only the failed ones again.

## The Error Prefixes

A message is only useful to a query if the query can tell one kind from another. So every `error_message` starts with a category, followed by a colon:

| Prefix | Meaning |
|---|---|
| `validation` | The request was wrong and nothing was sent. |
| `no_api_key` | No key was found in the environment or the JVM properties. |
| `timeout` | The service didn't answer within the time limits. |
| `io` | The connection failed: DNS, a reset, a refused connection. |
| `http_429` | The service said we're sending too much. |
| `http_5xx` | The service failed on its side. |
| `http_4xx` | The service rejected the request. |
| `malformed_response` | The service answered, but not with what we expected. |
| `interrupted` | The thread was interrupted while the call was running. |
| `internal` | Something unexpected happened in our own code. |

The prefix is the stable part. The text after it can change, but a query that checks `error_message STARTS WITH 'timeout'` keeps working. For example, to count a run's failures by category:

```cypher
WITH split(result.error_message, ':')[0] AS category
RETURN category, count(*) AS n
```

## Validation Before the Network

The cheapest failure is the one that never leaves the database. Before any request is sent, the function checks three things.

**1. The options.** The third argument is checked against a short list of known names: `model`, `connect_timeout_ms`, `request_timeout_ms` and `max_retries`. An unknown name is an error, not something to ignore. In chapter 2 we noted that `{modle: 'x'}` was silently dropped and the default model used instead. Now it isn't:

```cypher
RETURN jev.decide(
  {amount: 120.5, merchant_category: 'grocery'},
  {decision: {
     type: 'choice',
     instructions: 'Should this transaction be flagged or passed?',
     criteria: {Flag: 'Potential fraud.', Pass: 'Likely legitimate.'}
  }},
  {modle: 'jev-latest'}
) AS result
```

```
{
  answers: null,
  error_message: "validation: unknown option(s): modle; known options are [connect_timeout_ms, max_retries, model, request_timeout_ms]",
  metadata: {
    model: null,
    latency_ms: 0,
    attempts: 0
  }
}
```

The message names the bad option and lists the good ones. Note that `model` is null in the metadata. The options are the first thing read, so when they're wrong there's no model to report.

The numeric options have ranges too. Timeouts must be whole numbers and `max_retries` must be between 0 and 5.

**2. The questions.** `QuestionValidator` enforces the shapes from chapter 2. The map must not be empty and each question needs a `type` and `instructions`. A choice question needs its criteria as a map of label to description, with between 1 and 255 labels. A score question needs an ordered list of 2 to 10 level descriptions. Mixing the two shapes is the most common mistake and the API answers it with an HTTP 422, so we catch it first:

```cypher
RETURN jev.decide(
  {amount: 120.5, merchant_category: 'grocery'},
  {decision: {
     type: 'choice',
     instructions: 'Should this transaction be flagged or passed?',
     criteria: ['Flag', 'Pass']
  }}
) AS result
```

```
{
  answers: null,
  error_message: "validation: decision: a choice question needs criteria as a map of label to description, not an array",
  metadata: {
    model: "jev-latest",
    latency_ms: 0,
    attempts: 0
  }
}
```

The message starts with the question's name, `decision`, so with several questions you know which one to fix. Question types other than choice and score are checked only for `type` and `instructions`. The API decides which other types exist, so we don't second-guess it.

**3. The state.** A null state, or text that's blank, is rejected. So is anything JSON can't carry, such as NaN or infinity, with the path to the bad value, as in chapter 2:

```
{
  answers: null,
  error_message: "validation: state must not be blank",
  metadata: { model: "jev-latest", latency_ms: 0, attempts: 0 }
}
```

All three share two things. `latency_ms` is 0 and `attempts` is 0, because nothing touched the network. A validation failure costs nothing and is instant, which matters when the query runs over thousands of nodes.

## The Key

The function needs a key to call Jev. If it can't find one, in the environment variable or the JVM property, it returns a `no_api_key` error that says where to put it. The key is checked after validation, so a request that's wrong tells you so even before you've set a key up.

## Retries and Backoff

Some failures are worth trying again. A timeout may be a busy moment. A 429 means slow down. A 503 is the service having a bad second. Others are not. A 400 or 422 means the request is wrong and sending the same request again gets the same answer.

So the function retries only failures that might pass on their own:

- `timeout` and `io`, because the network can recover.
- `http_429` and `http_5xx`, because the service can recover.

Everything else, including every other 4xx, fails at once.

Between attempts the function waits and the wait doubles each time: 500 milliseconds before the second attempt, 1,000 before the third:

```java
sleeper.sleep(BACKOFF_MS << (attempt - 1));
```

Doubling gives a struggling service room to recover instead of hitting it again immediately. The defaults are a 5 second connect timeout, a 15 second request timeout and 2 retries, so up to 3 attempts. You can change any of them in the options. `max_retries` can be 0 if you'd rather fail fast.

Those defaults have a cost worth knowing about. In the worst case every attempt runs into the request timeout and one call can take close to 47 seconds: three waits of 15 seconds plus the two pauses. For a single call that's fine. For a thousand calls one after another it isn't and chapter 6 deals with that.

The `sleeper` in that line is a small interface:

```java
@FunctionalInterface
public interface Sleeper {
    void sleep(long millis) throws InterruptedException;
}
```

The real function passes `Thread::sleep`. The reason for the interface is the same as for `HttpTransport` in chapter 2. A test that proves the backoff works shouldn't have to wait for it and in chapter 4 it won't.

When the retries run out, the message says how many attempts were made. A single attempt reads "(after 1 attempt)" and more reads "(after 3 attempts)". The `attempts` field in the metadata carries the same number, so a query can find the calls that needed help.

## A Safety Net

Validation and the HTTP handling cover the failures we can predict. The rest is covered by a last catch around the whole call. Any unexpected exception, which in practice means a bug in our own code, becomes an `internal` error with the exception's class and message.

This is the part that makes "never throws" true instead of hopeful. Without it, the rule holds only as long as we've thought of everything. With it, even a mistake we haven't found yet arrives as a value in the result and one bad row still can't take the others down.

There's one exception to carrying on and it's deliberate. If the thread is interrupted, the function sets the interrupt flag again and returns an `interrupted` error. The interrupt is the database asking the work to stop, for instance when someone cancels a query. Swallowing it would make a long call impossible to cancel.

## Errors That Say Where

Two small habits make the errors easier to act on. First, they name the host. A timeout reads "no response from api.typesafe.ai within the configured limits" and an I/O failure names the host it was reaching, so you don't have to guess which service was involved. Second, an HTTP error includes the start of the response body, cut to 300 characters, because the service's own explanation is often the quickest route to the fix.

## Seeing It Work

Here's the four-transaction query again, with a `CASE` that makes the missing-node case explicit by passing null as the state. The function rejects a null state as a validation error:

```cypher
UNWIND ['T0001', 'T0002', 'BAD', 'T0003'] AS id
OPTIONAL MATCH (t:Transaction {txn_id: id})
WITH id, jev.decide(
  CASE WHEN t IS NULL THEN null ELSE
    t {.amount, .merchant_category, .distance_from_home_km, .hour_of_day,
       .account_age_days, .prior_flags, .txns_last_24h, .typical_monthly_spend} END,
  {decision: {
     type: 'choice',
     instructions: 'Based on all available signals, should this transaction be flagged for review or passed through?',
     criteria: {
       Flag: 'Potential fraud. Send for review.',
       Pass: 'Likely legitimate. Process normally.'
     }
  }}
) AS result
RETURN id,
       result.answers.decision.choice AS choice,
       result.error_message AS error_message,
       result.metadata.attempts AS attempts
```

```
╒═══════╤══════╤════════════════════════════════════╤════════╕
│id     │choice│error_message                       │attempts│
╞═══════╪══════╪════════════════════════════════════╪════════╡
│"T0001"│"Pass"│null                                │1       │
├───────┼──────┼────────────────────────────────────┼────────┤
│"T0002"│"Pass"│null                                │1       │
├───────┼──────┼────────────────────────────────────┼────────┤
│"BAD"  │null  │"validation: state must not be null"│0       │
├───────┼──────┼────────────────────────────────────┼────────┤
│"T0003"│"Pass"│null                                │1       │
└───────┴──────┴────────────────────────────────────┴────────┘
```

Three transactions got a decision and one got an explanation. The bad row used no attempts and the others used one each. Nothing stopped.

All three validation errors above are failures we can trigger on demand. The network failures are harder to show, because a real timeout or a real 503 isn't something you can order up. That's the other reason for the fake transport in the next chapter. It lets us produce any failure we like and check that the function responds the way this chapter says it does.

The statements from this chapter are also in `notebooks/03_failure_demos.ipynb`, which runs them one per cell and shows each result.

## What You'd Hit in Production

**The error is a value, so check it.** A query that reads `result.answers.decision.choice` gets null for a failed call, which looks the same as a missing property. If it matters, test `error_message IS NULL` first, or keep both fields.

**Retries add latency.** A flaky service can turn a half-second call into several seconds before it gives up. The `attempts` field is how you find out it happened.

**Retries can repeat work.** A timeout means we stopped waiting, not that the service stopped working, so a retried call may be processed twice. For a decision that only reads and answers, that's harmless. It's one more reason the function does no writing itself.

**Validation isn't perfect.** It catches the shapes we know about. The API remains the authority and anything it rejects that we let through comes back as an `http_4xx` with its own explanation.

## Going Further

We now have a function that fails gracefully and a set of claims about how: it retries these errors, doesn't retry those, backs off by doubling and names each category. So far we've only shown the failures we can cause from Cypher. In the next chapter we test all of it properly, without a network and then add live notebooks as a second layer that checks the real thing.
