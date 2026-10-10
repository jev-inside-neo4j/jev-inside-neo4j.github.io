# Chapter 4: Testing Without a Network

## The Problem with Testing a Network

Chapter 3 made a list of promises. The function retries a timeout but not a 422. It waits 500 milliseconds before the second attempt and 1,000 before the third. It reports "after 3 attempts" when it gives up. It never lets a bug escape as an exception.

We showed the promises that Cypher can trigger on demand, such as a bad option or a blank state. The rest are about the network and you can't order up a network failure. A real timeout happens when it happens. A real 503 comes from a service having a bad day. If the only way to check the retry logic were to wait for those, we'd never know whether it worked until the day it mattered.

So we take the network out of the picture.

## The Seam

We prepared for this in chapters 2 and 3, with two small interfaces.

`HttpTransport` is the only thing `JevClient` needs from the network. It takes a request and returns a status and a body, or throws an `IOException`. The real implementation, `JdkHttpTransport`, uses Java's own HTTP client. The client never touches the network directly.

`Sleeper` is the only thing `JevClient` needs from the clock. The real one is `Thread::sleep`.

Each interface is a seam: a place where we can replace the real thing with one we control. Neither changes the code under test. The client behaves the same way whether it's talking to Jev or to a stand-in.

## A Scripted Transport

The stand-in is `FakeTransport`. You give it a script of outcomes. Each one is either a response to return or an `IOException` to throw. It plays them back in order and records every request it receives:

```java
class FakeTransport implements HttpTransport {

    record Request(URI uri, Map<String, String> headers, String body,
                   Duration connect, Duration request) {}

    final List<Request> requests = new ArrayList<>();
    private final Deque<Object> outcomes = new ArrayDeque<>();
    private Object last;

    FakeTransport respond(int status, String body) {
        outcomes.add(new Response(status, body));
        return this;
    }

    FakeTransport fail(IOException e) {
        outcomes.add(e);
        return this;
    }

    @Override
    public Response post(URI uri, Map<String, String> headers, String body,
                         Duration connectTimeout, Duration requestTimeout) throws IOException {
        requests.add(new Request(uri, headers, body, connectTimeout, requestTimeout));
        Object next = outcomes.isEmpty() ? last : outcomes.poll();
        last = next;
        if (next instanceof IOException e) {
            throw e;
        }
        return (Response) next;
    }
}
```

When the script runs out, the last outcome repeats. That one rule makes a persistent failure easy to write. Queue a single 503 and every attempt gets a 503.

The recorded requests are the other half of the value. A test can check what was sent as well as what came back: how many attempts were made, what the headers were and what the body held.

For time, the test passes a different sleeper. Instead of sleeping, it adds the requested pause to a list:

```java
@BeforeEach
void setUp() {
    transport = new FakeTransport();
    sleeps = new ArrayList<>();
    key = "test-key";
    client = new JevClient(transport, mapper, () -> key, sleeps::add);
}
```

The test runs instantly and `sleeps` holds exactly the waits the function asked for.

## Testing Retries Without Waiting

Here's the promise about a service that keeps failing, as a test:

```java
@Test
void persistentServerErrorStopsAfterRetriesWithBackoff() {
    transport.respond(503, "unavailable");

    Map<String, Object> result = client.decide("x", questions(), Map.of());

    assertTrue(error(result).startsWith("http_5xx:"));
    assertTrue(error(result).contains("after 3 attempts"));
    assertEquals(3, transport.requests.size());
    assertEquals(List.of(500L, 1000L), sleeps);
    assertNull(result.get("answers"));
}
```

Read it as a list of the promises it checks. The category is `http_5xx`. The message says three attempts. The transport saw three requests. The waits were 500 and then 1,000 milliseconds. There are no answers. It's the chapter 3 text, line by line and it finishes in a few milliseconds instead of the second and a half the real pauses would take.

A recovery is the other case worth checking. One 429, then a success:

```java
@Test
void rateLimitThenSuccessRetriesOnce() {
    transport.respond(429, "slow down").respond(200, OK_BODY);

    Map<String, Object> result = client.decide("x", questions(), Map.of());

    assertNull(error(result));
    assertEquals(2L, metadata(result).get("attempts"));
    assertEquals(List.of(500L), sleeps);
}
```

The call succeeds, `attempts` says it took two and exactly one pause was requested.

Then the opposite, a failure that must not be retried:

```java
@Test
void clientErrorIsNotRetried() {
    transport.respond(422, "bad criteria shape");

    Map<String, Object> result = client.decide("x", questions(), Map.of());

    assertTrue(error(result).startsWith("http_4xx: 422"));
    assertEquals(1, transport.requests.size());
    assertTrue(sleeps.isEmpty());
}
```

One request and no pauses. A test for what the function doesn't do is as important as one for what it does, because it's the kind of promise that breaks quietly when someone changes the retry rules later.

The other network tests follow the same pattern:

- A long error body is cut to 300 characters and ends with "...".
- A timeout is retried, then reported with the `timeout` prefix.
- Any other I/O failure is retried, then reported with the `io` prefix and the original message.
- Setting `max_retries` to 0 means one attempt and "(after 1 attempt)".
- A 200 with a body that isn't JSON becomes `malformed_response`.
- So does a 200 with JSON that has no `answers`.

## Testing What Never Reaches the Network

Some of the most important tests check that nothing was sent. The transport records every request, so "no call was made" is a plain assertion:

```java
@Test
void missingKeyMakesNoCall() {
    key = null;

    Map<String, Object> result = client.decide("x", questions(), Map.of());

    assertTrue(error(result).startsWith("no_api_key:"));
    assertTrue(transport.requests.isEmpty());
}
```

The same check appears in the tests for invalid questions, invalid options, a null or blank state and a state of a type JSON can't carry. This is how we know the validation from chapter 3 happens before the network, not after.

A few tests look at what was sent. One parses the request body and checks the model, the Authorization header and the shapes of the criteria: an object for a choice question and an array for a score question. Another feeds in a state map with keys `b` then `a` and checks the text sent is `{"a":2,"b":1}`. That's the sorted-keys promise from chapter 2.

The remaining classes test the smaller parts on their own: `OptionsTest` for the option names, ranges and types, `QuestionValidatorTest` for the question shapes and `TypeNormalizerTest` for the conversions between Cypher's types and JSON's. They need no fake transport at all.

Maven runs all of them when you build. The build in `code/04-testing-without-a-network` ran 44 tests, 19 of them for the client, with no failures or errors:

```
Tests run: 11, Failures: 0, Errors: 0, Skipped: 0 -- QuestionValidatorTest
Tests run: 9,  Failures: 0, Errors: 0, Skipped: 0 -- TypeNormalizerTest
Tests run: 19, Failures: 0, Errors: 0, Skipped: 0 -- JevClientTest
Tests run: 5,  Failures: 0, Errors: 0, Skipped: 0 -- OptionsTest
Tests run: 44, Failures: 0, Errors: 0, Skipped: 0
```

The client's 19 tests, including every retry and backoff case, took under a tenth of a second together, because nothing waits. A failing test would have stopped the build before it produced a JAR. That's a useful property: the JAR you copy into `plugins` is one that passed its tests.

## What the Tests Don't Prove

A test against a fake proves one thing: the function does what we told it to do when the service behaves the way we told the fake to behave. It doesn't prove that Jev behaves that way.

Suppose Jev changed how it reports an error, the response gained a new field or the real service rejected a request the fake would have accepted. The unit tests would stay green. The fake is only as honest as our understanding of the service.

That's why there's a second layer. It runs the real function in the real database, through Cypher, against the real service.

## The Live Notebook

The notebook is `notebooks/04_decide_tests.ipynb` in the chapter's folder. It connects to your database with the Python driver and runs one `jev.decide` call per test, the same way a query of yours would. Each test prints PASS or FAIL and, at the end, everything is saved to a file, `results_decide.json`, so a run can be kept and compared.

A few choices shape it:

- **It never writes to the database.** Every test is a read-only `RETURN`.
- **It has four statuses.** PASS and FAIL are checks with a clear right answer. INFO records something measured, such as a latency. SKIPPED means a test couldn't run.
- **The hosted section is off by default.** A flag, `RUN_REAL_JEV`, controls whether it makes real calls, so a plain run costs nothing.
- **A re-run replaces earlier results.** Run a test cell again and its entry is updated, not duplicated.

This is the chapter 4 version. It tests what the function can do so far and later chapters add to it. The key rule joins in chapter 5 and the local model in chapter 7.

The first group of tests needs no key and makes no calls. It checks that the function is registered and that each kind of bad input produces a `validation:` error, no answers and zero attempts. That last part is how the notebook proves no request was made, without a fake. The function itself reports `attempts: 0`.

The second group is the optional one. With the flag on, it makes five real calls to Jev on one transaction and checks four things: no call returned an error, each took exactly one attempt, the choice is one we offered and the `confidence` equals the margin between the two probabilities. It allows a gap of 0.02 in that last check, because Jev rounds its probabilities to two decimals.

## Reading the Results

Here's the saved summary from a run with the flag on:

| Test | Status |
|---|---|
| function jev.decide is registered | PASS |
| validation: choice criteria as an array | PASS |
| validation: no call was made | PASS |
| validation: answers are null | PASS |
| validation: unknown option name | PASS |
| validation: blank state | PASS |
| validation: score criteria as a map | PASS |
| validation: retries out of range | PASS |
| validation: missing instructions | PASS |
| validation: no questions at all | PASS |
| real Jev: no errors | PASS |
| real Jev: one attempt each | PASS |
| real Jev: the choice is one we offered | PASS |
| real Jev: confidence is the margin between the two probabilities | PASS |
| real Jev: wall time per call (ms) | INFO |

Fourteen passes and one measurement. The detail column holds the error messages, which is a good way to see them all in one place. For example, the retries case reports `validation: max_retries must be between 0 and 5` and the missing-instructions case reports `validation: d: instructions are required`.

The real-Jev check on `confidence` has a detail worth reading: `confidence 0.04, margin 0.040000000000000036`. The two agree up to the floating-point noise in the last digits. This transaction, a cash withdrawal at midnight far from home, is one of the ambiguous kinds from chapter 1 and a margin of 0.04 means Jev could barely separate Flag from Pass. The choice was Flag.

The measurement is the wall time per call: a minimum of 217 milliseconds, a median of 235 and a maximum of 550. The first of the five calls was the slowest. This number includes the trip over Bolt and the query planning, so it's always a little more than the `latency_ms` the function reports about itself.

## What You'd Hit in Production

**A green suite can hide a bad fake.** The unit tests check the function against our model of the service. The notebook is the check on that model. When the service changes, the notebook is the layer that notices.

**Tests that wait are tests that get skipped.** If the retry tests slept for real, each would take seconds and sooner or later someone would stop running them. The `Sleeper` seam is what keeps them fast enough to run every time.

**The hosted section costs calls.** The notebook only reads, but the hosted section spends real API calls. That's why the flag defaults to off.

**Run the notebook against the build you installed.** The notebook tests whatever JAR is in the `plugins` folder. If you forget to swap in the new one and restart, it tests the old function.

## Going Further

We now have a function that makes a decision, fails gracefully and has tests at two levels. There's one problem left and it's about the API key. The function sends it in a header and the moment we let the endpoint be configured, the address decides where the key goes. In the next chapter we look at how that can go wrong and the rule that stops it.
