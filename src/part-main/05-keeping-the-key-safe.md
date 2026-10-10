# Chapter 5: Keeping the Key Safe

## The Key's Journey

Every call to Jev carries a secret: your API key, sent in an `Authorization` header. This chapter is about where that key lives, where it goes and how we made sure it can only go to one place.

Where it lives is simple. The function looks for `TYPESAFE_API_KEY` in an environment variable first and in a JVM property second. In chapter 2 we set it as a JVM option in the database's settings, because Neo4j Desktop starts the database for you and a variable exported in a terminal may never reach it.

The key never appears in a Cypher query, so it never lands in your query log. It never appears in a result either. The notebook from chapter 4 never sees it and one of the unit tests checks that a failed call's output doesn't contain it.

Where it goes is the interesting part.

## Why the Endpoint Must Be Configurable

Until now the function called one address, hard-wired in the code: Jev's. That's the safest possible design for a key, because there's only one place it can go.

But chapter 7 uses the same function against a local model and that needs a different address. So the third argument gains a new option, `url`:

```cypher
RETURN jev.decide($state, $questions, {url: 'http://localhost:11434/v1/systemone'})
```

The moment the address can change, the question of what to do with the key becomes a real one.

## The Flaw

The obvious design for this option is to read the address from the options, send the request there and attach the key, as before.

We found the problem with that design while testing, before the option shipped. With it, the key goes wherever the caller points it. A typo sends it to the wrong place. A query built from untrusted input, such as a value read from a node property, could send it to an address someone else chose. Nothing was ever sent anywhere it shouldn't have been. The flaw was in what the design allowed.

A credential should go to the service it belongs to and nowhere else. The code should enforce that rather than rely on every caller getting every address right.

## The Tempting Fix

The first fix that comes to mind is to check the address as text. Send the key only if the URL starts with `https://api.typesafe.ai`. Or only if it contains `api.typesafe.ai`.

Both are fooled by addresses that look right and aren't. Two examples:

```
https://api.typesafe.ai@evil.com/v1/systemone
https://api.typesafe.ai.evil.com/v1/systemone
```

The first one starts with exactly the right text. But in a URL, everything before an `@` is a user name, so the host the connection goes to is `evil.com`. The second starts with the right text as well. It's a subdomain of `evil.com` and whoever controls that domain controls where it points.

A check on the raw text can't tell these apart from the real thing, because the text isn't what the network connects to. The host is.

## The Rule

So the rule works on the parsed address, not the text. The key is sent only if the scheme is `https` and the host is exactly `api.typesafe.ai`:

```java
public boolean sendsApiKey() {
    return "https".equalsIgnoreCase(url.getScheme()) && KEY_HOST.equalsIgnoreCase(url.getHost());
}
```

Java's own URL parser decides what the host is, the same way the HTTP client will when it connects, so there's no gap between the check and the connection. For the two tricks above, the parser reports `evil.com` and `api.typesafe.ai.evil.com`. Neither equals `api.typesafe.ai`, so neither gets the key.

The rule is strict in other ways too. Plain `http` to the real host doesn't get the key, because it would cross the network in the clear. Any other host, such as a local server, doesn't either. The port isn't part of the check, so `https://api.typesafe.ai:8443/` is still the real host.

What happens to the request in the client follows from this one method:

```java
boolean authenticated = options.sendsApiKey();
Map<String, String> headers = new LinkedHashMap<>();
headers.put("Content-Type", "application/json");
if (authenticated) {
    String key = apiKey.get();
    if (key == null || key.isBlank()) {
        return failure("no_api_key: set TYPESAFE_API_KEY as an environment variable or JVM property",
                model, started, 0, false);
    }
    headers.put("Authorization", "Bearer " + key);
}
```

There are two details here. First, the key is read inside the `if`. For any other endpoint the key supplier isn't called at all, so the key doesn't even pass through the code that builds that request. A test checks it by supplying a key function that throws if it's called.

Second, a missing key is only an error for the real endpoint. A local model needs no key, so a call to one works on a machine where no key has ever been set.

## Seeing It

The result now says what happened. A new field in the metadata, `authenticated`, tells you whether the key went out. Here's a normal call to the real endpoint:

```
{
  answers: {
    decision: {
      type: "choice",
      choice: "Pass",
      probabilities: {
        Pass: 0.91,
        Flag: 0.09
      },
      confidence: 0.82
    }
  },
  error_message: null,
  metadata: {
    authenticated: TRUE,
    model: "jev-latest",
    latency_ms: 312,
    attempts: 1
  }
}
```

Now the same function pointed at another address. Nothing listens on port 9 of your own machine, so the connection is refused:

```cypher
RETURN jev.decide(
  {amount: 120.5, merchant_category: 'grocery'},
  {decision: {
     type: 'choice',
     instructions: 'Should this transaction be flagged or passed?',
     criteria: {Flag: 'Potential fraud.', Pass: 'Likely legitimate.'}
  }},
  {url: 'http://127.0.0.1:9/v1/systemone', max_retries: 0}
) AS result
```

```
{
  answers: null,
  error_message: "io: ConnectException reaching 127.0.0.1:9 (after 1 attempt)",
  metadata: {
    authenticated: FALSE,
    model: "jev-latest",
    latency_ms: 0,
    attempts: 1
  }
}
```

The failure is reported as data, as in chapter 3. The message names the host and the port, so a wrong `url` is easy to spot and `authenticated` is false. That's the rule at work. This address wasn't the real Jev, so no key went with the request.

Leave `max_retries` at its default and the same call makes three attempts, with the pauses from chapter 3:

```
{
  answers: null,
  error_message: "io: ConnectException reaching 127.0.0.1:9 (after 3 attempts)",
  metadata: {
    authenticated: FALSE,
    model: "jev-latest",
    latency_ms: 1515,
    attempts: 3
  }
}
```

The call took 1,515 milliseconds, just over the 500 plus 1,000 milliseconds of waiting. That's the backoff arithmetic from chapter 3 showing up in a real measurement.

The statements from this chapter are also in `notebooks/05_key_demos.ipynb`, which runs them one per cell and shows each result.

## Errors That Name the Host

The error messages now name wherever the call was heading, not just the real host. Before this chapter an I/O failure always said `api.typesafe.ai`. Now it says the configured host and port, so with a local model a refused connection reads "reaching localhost:11434" and tells you at once that Ollama isn't running.

The name is built from the parsed address too and it leaves out anything before an `@`. A URL such as `https://user:secret@evil.com/x` appears in an error as `evil.com`. Error messages end up in query results and logs and a password someone put in an address shouldn't end up there with them.

## Testing the Rule

The rule has to hold for every input, so it's tested from both sides.

The unit tests check it directly. `OptionsTest` checks that the key is sent only to `https://api.typesafe.ai` and that case doesn't matter. It checks that it's never sent to `localhost`, to another site or over plain `http` and that the look-alike addresses don't get it. Several tests in `JevClientTest` go further and look at the request the fake transport received. A local endpoint gets no `Authorization` header. The key isn't sent to another host even when one is configured. An address with `@evil.com` is sent to `evil.com` and still without the key. The key function isn't called for non-Jev endpoints. Together with the tests from earlier chapters there are now 55 and all of them passed.

The notebook, `notebooks/05_decide_tests.ipynb` in this chapter's folder, checks it against the real function. It points `jev.decide` at two look-alike addresses. Both end in `.invalid`, a name reserved so that it never resolves, so the test sends nothing to a real server. One adds a suffix to the right name. The other puts the right name in front of an `@`. Both should come back with `authenticated` false and not with a `no_api_key` error.

Here's what the notebook recorded:

| Test | Status | Detail |
|---|---|---|
| key rule: not sent to a look-alike (suffix on the real name) | PASS | |
| key rule: no key was needed (suffix on the real name) | PASS | |
| key rule: error text (suffix on the real name) | INFO | io: ConnectException reaching api.typesafe.ai.invalid (after 1 attempt) |
| key rule: not sent to a look-alike (real name as a user name) | PASS | |
| key rule: no key was needed (real name as a user name) | PASS | |
| key rule: error text (real name as a user name) | INFO | io: ConnectException reaching lookalike.invalid (after 1 attempt) |

The two INFO rows are the best evidence. For the address that starts with `https://api.typesafe.ai@`, the error says the function was reaching `lookalike.invalid`. That's what the parser decided the host was and since it isn't the real host, no key was sent. The text checks we rejected earlier would have sent it.

The rest of the run was clean as well. The notebook recorded 25 passes and 3 observations. That includes the closed-port checks, which show 1,516 ms for three attempts and the hosted section: five real calls, no errors, the key sent each time and one attempt each.

One more detail from that hosted run. The fixture is the midnight cash withdrawal from chapter 4 and this time Jev chose Pass, with `confidence 0.08, margin 0.08000000000000002`. In chapter 4 the same fixture gave Flag with a margin of 0.04. The transaction is a genuinely ambiguous one and Jev's answers on it aren't guaranteed to be the same from one run to the next.

## What You'd Hit in Production

**Point `url` at a service you trust and check what it's for.** The rule protects the Jev key. It doesn't make every endpoint safe to call. A request to a local server or any other address still carries your transaction's data, only without the key.

**Notice `authenticated: false`.** If you expect a call to go to Jev and see false, the address is wrong. It may be a typo, a trailing difference in the host or `http` instead of `https`.

**A missing key shows up only for the real endpoint.** A `no_api_key` error means the call was heading for Jev and the key wasn't found. If you see it, check the environment variable and the JVM option, as in chapter 2.

**Keys in `neo4j.conf` are still keys in a file.** The JVM option keeps the key out of queries and results, but it sits in the database's configuration. Treat that file like any other place a secret lives.

## Going Further

The function now makes a decision, fails gracefully, has tests at two levels and sends its key to exactly one place. Everything so far works on one transaction at a time. The next chapter takes it to the whole graph: a batch procedure, a bounded thread pool and 500 decisions in one statement.
