# Chapter 1: Why Decisions Belong in the Database

## The Problem

A graph is good at questions about structure: who is connected to whom, how far apart two nodes are, which paths loop back on themselves. It isn't good at questions of judgment. Is this transaction suspicious? Does this support ticket sound urgent? Is this review about shipping or about the product? Questions like these have no Cypher pattern, because the answer isn't in the shape of the graph. It's in the meaning of the properties.

Today the usual route to an answer looks like this:

```
Neo4j -> export -> script -> model -> script -> import -> Neo4j
```

It works, but it has costs that show up every time:

- **The data leave the database.** Every export is a copy and every copy needs somewhere to live and someone to look after it.
- **The glue code is yours.** Retries, timeouts, rate limits and bad responses get solved again in every project, usually the second time something breaks.
- **The answers arrive detached.** They come back as a file and have to be matched to the right nodes before they're any use.
- **The decision isn't part of the query.** You can't ask a question about your graph and have a model's judgment as one step in the middle of it.

The last cost matters most. If a decision were just another step in a Cypher statement, we could filter on it, group by it and traverse from it, like any other property.

## The Idea

We make the decision a function. You call it from Cypher the way you'd call any built-in function and it returns an answer:

```
jev.decide(state, questions [, options])
```

The three arguments are simple:

- **`state`** describes the thing to decide about. In our case it's a map of a node's properties.
- **`questions`** says what to decide. A *choice* question picks one option from a set of labeled options. A *score* question rates the state against an ordered list of levels.
- **`options`** is optional and sets things like the model, the endpoint and the timeouts.

The function returns one map with three parts: the `answers` (with a probability for each option of a choice question), an `error_message` and some `metadata` about the call.

One design decision shapes the whole book: the function never throws. If the service is down, the key is missing or the question is malformed, you get an `error_message` and no answers and the query carries on. We come back to this in chapter 3.

A second piece, `jev.decideAll`, is a procedure that takes many states and returns one result per state. It's what turns the function from a convenient call into something that can work through a whole graph and chapter 6 is about it.

The service behind the function is Jev, which takes a state and a set of questions and returns an answer with probabilities. The function speaks a simple HTTP API, so the same call can also go to a local model server. We use that in chapter 7.

## The Use Case

Our running example is a small one. We have a set of `Transaction` nodes and for each one we want to answer a single question: should this transaction be flagged for review or passed through?

The function returns a decision with probabilities attached. We store each decision as a `Decision` node linked to its transaction. After that, everything we want to know is a query: which flagged transactions was Jev least sure about, which passes might be missed fraud, how much of the graph can be handled without a person looking at it.

We build this example up one piece at a time. Each chapter adds a part of the code and uses only what's been built so far, so there's something working from chapter 2 onward and nothing the reader has to take on trust.

## The Data

The dataset is 500 synthetic transactions generated with a fixed random seed, so everyone works with the same ones. Each `Transaction` node has eight properties that the function gets to see:

- `amount`
- `merchant_category`
- `distance_from_home_km`
- `hour_of_day`
- `account_age_days`
- `prior_flags`
- `txns_last_24h`
- `typical_monthly_spend`

Each node also carries three properties that the function never sees: `scenario`, `true_p` and `is_fraud`. These are the generator's answers. They're stored with the data so we can check the decisions later and they never become part of a request.

The transactions come from scenarios such as routine spending, a traveler far from home, a late-night cash withdrawal and a card being tested with tiny purchases. Each scenario has a latent probability of fraud and the label is drawn from it. A routine purchase is almost never fraud and a clear case of fraud almost always is, while a late-night withdrawal sits in the middle. This is deliberate. A real judge has to live with cases that are genuinely ambiguous and so does ours.

The graph model for the example is:

```
(:Transaction {txn_id, amount, merchant_category, distance_from_home_km,
               hour_of_day, account_age_days, prior_flags, txns_last_24h,
               typical_monthly_spend, scenario, true_p, is_fraud})
  -[:HAS_DECISION]->
(:Decision {choice, confidence, p_flag, p_pass,
            latency_ms, attempts, model})
```

Chapter 6 creates the `Decision` nodes, when we store the results of a full run.

## A Preview of the Result

Here's where we're heading. There's nothing to run yet. This is the call we'll make in chapter 2, on one transaction:

```cypher
RETURN jev.decide(
  {
    amount: 2992.06,
    merchant_category: 'travel',
    distance_from_home_km: 8.1,
    hour_of_day: 14,
    account_age_days: 1483,
    prior_flags: 0,
    txns_last_24h: 2,
    typical_monthly_spend: 3230
  },
  {
    decision: {
      type: 'choice',
      instructions: 'Based on all available signals, should this transaction be flagged for review or passed through?',
      criteria: {
        Flag: 'Potential fraud. Send for review.',
        Pass: 'Likely legitimate. Process normally.'
      }
    }
  }
) AS result
```

And this is the result, from a real run:

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

Jev passed the transaction, with 91% of the probability on Pass and 9% on Flag. We unpack the other fields in chapter 2.

Once the decisions are stored, a question that used to need an export and a script is a single query:

```cypher
MATCH (t:Transaction)-[:HAS_DECISION]->(d:Decision)
WHERE d.choice = 'Flag'
RETURN t.txn_id, t.amount, d.p_flag
ORDER BY d.p_flag ASC
LIMIT 5
```

This lists the five flagged transactions the decision was least sure about, which is where a person should look first. The property names are the ones chapter 6 creates.

## The Companion Repository

Everything in this book lives in one repository: the text of each chapter, the code and the data. You can find it at:

[github.com/jev-inside-neo4j/jev-inside-neo4j.github.io](https://github.com/jev-inside-neo4j/jev-inside-neo4j.github.io).

```
jev-inside-neo4j.github.io/
  src/part-main/                  the text of each chapter
  code/
    data/                         transactions.csv
    02-first-working-function/    the project as it stands at the end of chapter 2
    03-.../                       and one folder for each chapter after that
```

Each chapter that has code gets a folder in `code` with the same name as the chapter. Every folder is a complete project that builds on its own, so you can start from any chapter. The JAR has the same name in every folder, which means a newer one simply replaces the older one.

To get the files, clone the repository. In GitHub Desktop, choose File, then Clone Repository, open the URL tab, paste the address above, pick a folder on your machine and click Clone. If you prefer the command line, `git clone` with the same address does the same job.

## What the Book Builds

Each chapter takes one lesson from the build and adds the code that goes with it:

- **Chapter 2, A First Working Function.** The project setup, how Neo4j treats the types that cross the boundary and the first live call.
- **Chapter 3, Failures as Data.** Why the function returns an error instead of throwing and the categories of error it can return.
- **Chapter 4, Testing Without a Network.** A fake transport, retry and backoff tests and live notebooks as a second layer.
- **Chapter 5, Keeping the Key Safe.** A leak we found while testing and the rule that closed it.
- **Chapter 6, Many at Once.** The batch procedure, a bounded thread pool and what the timings show.
- **Chapter 7, Running It Locally.** The same function against a local model and what changes.

## What You'll Need

- **Neo4j Desktop.** The function is a plugin, so it runs in a local database. Download from [Neo4j for Desktop](https://neo4j.com/download/).
- **A Java development kit and Maven.** We build the plugin ourselves.
- **A Jev API key.** Chapter 2 shows where to put it and chapter 5 shows how the function reads it and where it sends it.
- **Git or GitHub Desktop.** To clone the book's repository.
- **Optionally, Ollama.** Chapter 7 uses it to run a small model on your own machine.

The versions of every tool and library are pinned in the code files that come with the book.

In the next chapter we build the first version of the function and make the call from the preview on a real transaction.
