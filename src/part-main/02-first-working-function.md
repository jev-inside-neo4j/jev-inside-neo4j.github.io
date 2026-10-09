# Chapter 2: A First Working Function

## What Is It?

Neo4j lets you add your own functions to Cypher. You write a Java class, mark a method with `@UserFunction`, package it as a JAR and drop it in the database's `plugins` folder. After a restart, your function sits next to the built-in ones and you call it the same way.

Our function is `jev.decide`. This chapter builds the smallest version that works: one call, one transaction, one answer. It has no retries, no validation and no clever error handling. Those come later and each one arrives because something went wrong without it.

A user-defined function has three parts:

- **A name.** The `@UserFunction` annotation gives it, here `jev.decide`. The part before the dot is a namespace, which keeps our function from colliding with anything else.
- **Parameters.** Each one is marked with `@Name`. A parameter can have a default, which is how the third argument becomes optional.
- **A return value.** Whatever the method returns, Neo4j hands back to Cypher.

The types at the boundary follow a fixed mapping:

| Cypher | Java |
|---|---|
| Integer | `Long` |
| Float | `Double` |
| String | `String` |
| Boolean | `Boolean` |
| List | `List` |
| Map | `Map` |
| null | `null` |

A parameter declared as `Object` accepts any of these, which is how our `state` argument can be either text or a map.

## The Project

The project is small. Five files and a pom. You'll find them in the `code` folder of the book's repository:

```
code/02-first-working-function/
  pom.xml
  load-and-call.cypher
  notebooks/01_load_and_call.ipynb   the same statements, one per cell
  src/main/java/com/example/jevdecide/
    JevDecide.java          the function Neo4j sees
    JevClient.java          builds the request and reads the response
    HttpTransport.java      the one thing the client needs from the network
    JdkHttpTransport.java   the real transport, built on Java's own HTTP client
    TypeNormalizer.java     moves values between Cypher's types and JSON's
```

The pom needs two decisions.

First, Neo4j itself is a **provided** dependency. The database supplies its own API when it loads our JAR, so we compile against it and leave it out of the package.

Second, we bring our own JSON library, Jackson, and **relocate** it. Neo4j carries its own copy of Jackson and two copies of the same library on one class path is a recipe for strange failures. The Maven shade plugin moves our copy into a package of our own, so the two never meet:

```xml
<relocations>
  <relocation>
    <pattern>com.fasterxml.jackson</pattern>
    <shadedPattern>com.example.jevdecide.shaded.jackson</shadedPattern>
  </relocation>
</relocations>
```

The shade plugin also needs to leave Neo4j's classes out of the finished JAR:

```xml
<artifactSet>
  <excludes>
    <exclude>org.neo4j:*</exclude>
  </excludes>
</artifactSet>
```

The versions of the tools and libraries are pinned in the pom, not in this text.

`JevDecide` is deliberately thin. Neo4j sees this class and everything it does is hand the arguments to `JevClient`:

```java
@UserFunction("jev.decide")
@Description("Ask Jev typed questions about a state. Returns {answers, error_message, metadata}. "
        + "In this first version a failure raises an error.")
public Map<String, Object> decide(
        @Name("state") Object state,
        @Name("questions") Map<String, Object> questions,
        @Name(value = "options", defaultValue = "{}") Map<String, Object> options) {
    return CLIENT.decide(state, questions, options);
}
```

Keeping the logic out of this class matters. Anything that needs a running Neo4j to test is slow to test. Anything in `JevClient` doesn't and chapter 4 takes advantage of that.

## The Type Rules

The obvious way to build the result is to return the answers exactly as Jackson parsed them. That doesn't work and the reason is in the mapping table above.

Neo4j accepts `Long` for integers and nothing smaller. Jackson, given a small whole number in JSON, produces an `Integer`. A probability such as `0.93` is fine, because it becomes a `Double`. But a whole number in the response, such as a score of `1`, arrives as an `Integer` and that's a type Neo4j won't take back.

`TypeNormalizer` solves it in both directions. On the way back it widens everything to the types Neo4j accepts:

```java
public static Object fromJson(Object value) {
    if (value == null || value instanceof String || value instanceof Boolean
            || value instanceof Long || value instanceof Double) {
        return value;
    }
    if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
        return ((Number) value).longValue();
    }
    if (value instanceof Float) {
        return ((Number) value).doubleValue();
    }
    // ... BigInteger, BigDecimal, Map and Collection are handled the same way
    return String.valueOf(value);
}
```

On the way out it does the opposite job. It checks what Cypher handed us and rejects anything JSON can't carry:

```java
if (value instanceof Double || value instanceof Float) {
    double d = number.doubleValue();
    if (Double.isNaN(d) || Double.isInfinite(d)) {
        throw new IllegalArgumentException(path + ": NaN and infinity can't be sent as JSON");
    }
}
```

The `path` in that message is the location of the bad value, such as `$.amount`, so the error says where to look. A map whose keys aren't text is rejected the same way and so is a type we don't recognize.

## The Request

The API takes three things: a model name, a state and a set of questions.

The state is text. If we're given text, we send it as it is. If we're given a map or a list, we serialize it and we sort the keys first:

```java
private String stateText(Object state) throws JsonProcessingException {
    if (state instanceof String text) {
        return text;
    }
    return mapper.writeValueAsString(TypeNormalizer.toJson(state, true));
}
```

Sorting means the same properties always produce the same request, whatever order Cypher happened to hand them over in. That matters for anything we want to compare later.

For our first transaction the request body looks like this:

```json
{
  "model": "jev-latest",
  "state": "{\"account_age_days\":1483,\"amount\":2992.06,\"distance_from_home_km\":8.1,\"hour_of_day\":14,\"merchant_category\":\"travel\",\"prior_flags\":0,\"txns_last_24h\":2,\"typical_monthly_spend\":3230}",
  "questions": {
    "decision": {
      "type": "choice",
      "instructions": "Based on all available signals, should this transaction be flagged for review or passed through?",
      "criteria": {
        "Flag": "Potential fraud. Send for review.",
        "Pass": "Likely legitimate. Process normally."
      }
    }
  }
}
```

A choice question takes its options as a map of label to description. A score question takes its levels as an ordered list and mixing the two shapes is an error the API rejects. We don't check for that yet. Chapter 3 does.

Sending the request is the job of `HttpTransport`, an interface with a single method:

```java
public interface HttpTransport {

    record Response(int status, String body) {}

    Response post(URI uri,
                  Map<String, String> headers,
                  String body,
                  Duration connectTimeout,
                  Duration requestTimeout) throws IOException, InterruptedException;
}
```

The real implementation, `JdkHttpTransport`, uses the HTTP client that ships with Java, so there's nothing extra to install. The reason for the interface is that `JevClient` never touches the network directly. It asks an `HttpTransport` to do it. That's the seam chapter 4 uses to test the client without a network at all.

The heart of `JevClient` is short enough to read in one go:

```java
String body = mapper.writeValueAsString(requestBody(state, questions, model));
HttpTransport.Response response =
        transport.post(ENDPOINT, headers, body, CONNECT_TIMEOUT, REQUEST_TIMEOUT);

if (response.status() != 200) {
    throw new IllegalStateException("Jev returned HTTP " + response.status() + ": " + response.body());
}

Map<String, Object> root =
        mapper.readValue(response.body(), new TypeReference<Map<String, Object>>() { });
Object answers = root == null ? null : root.get("answers");
if (!(answers instanceof Map)) {
    throw new IllegalStateException("the response has no answers object");
}
return result(TypeNormalizer.fromJson(answers), model, started);
```

Build the request, send it, check the status, pull out the `answers` and normalize them. The result is a map with the answers, an `error_message` (always empty in this version) and some `metadata`.

The only option this version reads is `model`. If you don't give one, it uses `jev-latest`.

## The Data Goes In

Before the first call we need something to decide about. The repository you cloned in chapter 1 includes a file, `code/data/transactions.csv`, with 500 transactions. Copy that file into the import folder of your Neo4j Desktop database. Desktop has an option to open a database's folders and the import folder is one of them.

Then load it:

```cypher
CREATE CONSTRAINT txn_id IF NOT EXISTS
FOR (t:Transaction) REQUIRE t.txn_id IS UNIQUE;

LOAD CSV WITH HEADERS FROM 'file:///transactions.csv' AS row
MERGE (t:Transaction {txn_id: row.txn_id})
SET t.amount                = toFloat(row.amount),
    t.merchant_category     = row.merchant_category,
    t.distance_from_home_km = toFloat(row.distance_from_home_km),
    t.hour_of_day           = toInteger(row.hour_of_day),
    t.account_age_days      = toInteger(row.account_age_days),
    t.prior_flags           = toInteger(row.prior_flags),
    t.txns_last_24h         = toInteger(row.txns_last_24h),
    t.typical_monthly_spend = toInteger(row.typical_monthly_spend),
    t.scenario              = row.scenario,
    t.true_p                = toFloat(row.true_p),
    t.is_fraud              = toInteger(row.is_fraud);
```

A CSV file is all text, so every value has to be converted. The conversions aren't arbitrary. The state goes to Jev as text, so a whole number should stay a whole number and a decimal should stay a decimal. `hour_of_day` is `14`, not `14.0`.

The `MERGE` on the constraint means you can run this twice and still have 500 nodes.

## The First Call

Install the JAR and tell Neo4j about it:

1. Build the project with Maven by running `mvn clean package` in `code/02-first-working-function`. The JAR appears in the project's `target` folder.
2. Copy the JAR into the database's `plugins` folder.
3. In the database's config settings file `neo4j.conf`, allow the new function with `dbms.security.procedures.allowlist=jev.*`.
4. In the same settings, give the database your API key as a JVM option: `server.jvm.additional=-DTYPESAFE_API_KEY=your-key`.
5. Restart the database.

Now the call. The map projection `t {.amount, ...}` picks out the eight visible properties and nothing else, so `scenario`, `true_p` and `is_fraud` never reach Jev:

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
  }}
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
        Pass: 0.91,
        Flag: 0.09
      },
      confidence: 0.82
    }
  },
  error_message: null,
  metadata: {
    model: "jev-latest",
    latency_ms: 349
  }
}
```

Reading it from the top:

- **`choice`** is the option Jev picked. Here it passed the transaction.
- **`probabilities`** says how likely Jev thinks each option is. They add up to 1.
- **`confidence`** is the gap between the two probabilities: 0.91 minus 0.09 is 0.82. A gap of 0 would be a coin flip and a gap of 1 would be certainty. It isn't the probability of being right, which is the 0.91 next to `Pass`.
- **`type`** repeats the kind of question we asked.
- **`error_message`** is empty, because the call worked.
- **`metadata`** says which model answered and how long the call took, 349 milliseconds here. In this first version it has two fields. Chapters 3 and 5 add `attempts` and `authenticated`.

For the record, the label we hid says T0001 isn't fraud, so Pass was the right call.

That's a decision made inside the database. The query took a node, handed its properties to a function and got an answer back as a value. Nothing was exported.

The same statements are also in `notebooks/01_load_and_call.ipynb`, which runs them one per cell and shows each result.

## What You'd Hit in Production

**The JAR needs a restart.** Neo4j loads plugins when it starts. Copy in a new JAR and nothing changes until you restart the database. Two JARs that both define `jev.decide` will also clash, so remove the old one first. Every chapter's JAR has the same name, so copying a new one over the old one replaces it.

**The allowlist.** Without `jev.*` in the procedure allowlist, Neo4j refuses to run the function and the failure is easy to mistake for a bug in the code. Check the allowlist first.

**The key doesn't arrive.** Neo4j Desktop starts the database for you, so a variable you exported in a terminal may never reach it. A JVM option in the database settings always does. The function looks for an environment variable first and the JVM property second.

**A failure fails the whole query.** In this version, a bad key, a network error or a timeout raises an error. Run the function over 500 nodes and one failure stops the lot. That's the lesson of the next chapter.

**A typo is silently ignored.** Pass `{modle: 'x'}` as the options and the function won't complain. It simply uses the default model. The same chapter fixes that too.

## Going Further

We have a function that makes one decision and it works as long as everything goes right. In practice a network call fails in many ways: the key's missing, the service is slow, the response isn't what we expected. Right now every one of those stops the query. In the next chapter we change what the function does when something goes wrong and the answer is the idea that shapes the rest of the book: a failure is just another value.
