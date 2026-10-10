# Chapter 1: Why Decisions Belong in the Database

## The Problem

A graph is good at questions about structure: who is connected to whom, how far apart two nodes are, which paths loop back on themselves. It isn't good at questions of judgment. Is this transaction suspicious? Does this support ticket sound urgent? Is this review about shipping or about the product? Questions like these have no Cypher pattern, because the answer isn't in the shape of the graph. It's in the meaning of the properties.

Today the usual route to an answer looks like this:

```
Neo4j -> export -> script -> model -> script -> import -> Neo4j
```

It works, but it has costs that show up every time:

- **The data leaves the database.** Every export is a copy and every copy needs somewhere to live and someone to look after it.
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

Cloning the repository, building a chapter and running the notebooks are covered in "Setting Up" at the end of this chapter. A table of every file you can run is in "What's in the Repository", right after it.

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
- **A Jev API key.** Chapter 5 shows how the function reads it and where it sends it.
- **Git or GitHub Desktop.** To clone the book's repository.
- **Optionally, Ollama.** Chapter 7 uses it to run a small model on your own machine.

The versions of every tool and library are pinned in the code files that come with the book.

## Setting Up

This section takes you from nothing to a working function. Chapter 2 walks through the same steps again with the reasons behind them, so here we keep to the commands. Where a step runs Cypher, run the statements one at a time. A whole script at once only tells you that it succeeded and most of the statements are there for the result they show.

**1. Get the repository.** On the command line:

```shell
git clone https://github.com/jev-inside-neo4j/jev-inside-neo4j.github.io.git
cd jev-inside-neo4j.github.io
```

In GitHub Desktop, choose File, then Clone Repository, open the URL tab, paste `https://github.com/jev-inside-neo4j/jev-inside-neo4j.github.io`, pick a folder on your machine and click Clone. Either way you end up with a folder named `jev-inside-neo4j.github.io`. All paths in this book are relative to it.

**2. Check Java and Maven.** Both commands should print a version and not an error:

```shell
java -version
mvn -version
```

If either one fails, install it first. The versions the project needs are set in each chapter's `pom.xml`.

**3. Build a chapter.** Each chapter's folder is a complete project. To build chapter 2:

```shell
cd code/02-first-working-function
mvn clean package
```

The JAR appears in the folder's `target` directory as `neo4j-jev-decide-1.0.0.jar`. From chapter 4 on, the folders also have unit tests. Run them on their own with:

```shell
mvn test
```

The tests use a fake transport, so they need no network and no key. `mvn clean package` runs them too.

**4. Install the plugin.** In Neo4j Desktop:

1. Stop the database.
2. On the instances page, click the **Open Folder** button next to the database. It shows the folders Desktop uses, such as `config`, `import` and `plugins`. Copy the JAR into the `plugins` folder.
3. Open the configuration file, `neo4j.conf`, in the `config` folder and add these two lines, with your own key in place of the placeholder:

```text
dbms.security.procedures.allowlist=jev.*
server.jvm.additional=-DTYPESAFE_API_KEY=your-api-key
```

4. Start the database and check that the function is registered:

```cypher
SHOW FUNCTIONS YIELD name WHERE name STARTS WITH 'jev' RETURN name
```

You should see `jev.decide`. From chapter 6 on, you'll also see `jev.decideAll` under `SHOW PROCEDURES`. Don't commit the key anywhere. It stays in `neo4j.conf` and nowhere else.

Each chapter's JAR has the same name, so moving to another chapter means stopping the database, replacing the JAR in `plugins` with the new one and starting the database again. Only one chapter's JAR can be installed at a time.

**5. Load the data.** Copy `code/data/transactions.csv` into the database's `import` folder. You'll find it with `plugins` and the other folders behind the Open Folder button. The chapter 2 notebook `02_load_and_call.ipynb`, loads the file and makes the first call. Run it before any later chapter, because every later chapter uses the 500 transactions it creates.

**6. Set up the notebooks.** The notebooks run in Jupyter and connect to your database over Bolt. Create a virtual environment, activate it and install Jupyter in it:

```shell
python3 -m venv myenv
source myenv/bin/activate
```

Then set the connection details in the same terminal, before you launch Jupyter, because the notebooks read them from the environment:

```shell
export NEO4J_URI="bolt://localhost:7687"
export NEO4J_USERNAME="neo4j"
export NEO4J_PASSWORD="your-password"
export NEO4J_DATABASE="neo4j"
```

On Windows, use `set` in place of `export` and `myenv\Scripts\activate` in place of `source`. Each notebook installs the exact versions of the two libraries it needs in its first code cell, so there is nothing else to install. The notebooks never see your API key. The database reads it from `neo4j.conf`.

**7. Optionally, set up Ollama.** Chapter 7 is the only chapter that uses it. Install Ollama, then pull the model once:

```shell
ollama pull tev1:0.8b
```

Leave Ollama running while you work through chapter 7.

**If something doesn't work**

- **`SHOW FUNCTIONS` doesn't list `jev.decide`.** The JAR isn't in `plugins`, the allowlist line is missing from `neo4j.conf` or you didn't restart the database after changing either one.
- **A call returns `no_api_key`.** The `server.jvm.additional` line is missing or misspelled. Check it and restart the database.
- **A chapter's notebook fails on something the chapter introduced.** You may have an earlier chapter's JAR installed. Install the JAR from the chapter you're reading and restart.
- **A chapter 7 call returns an `io` error naming `localhost:11434`.** Ollama isn't running.
- **A notebook can't connect.** The environment variables were set after Jupyter started. Stop Jupyter, set them and start it again from the same terminal.

## What's in the Repository

Every file you run is in a chapter's folder under `code`, apart from the data file. The `.cypher` files hold the statements the chapter shows. The notebooks run the same statements one per cell and show each result. Run the statements one at a time, in order. The test notebooks check the function against the real services and save what they found in a JSON file next to them.

| File | Chapter | What it does | Needs |
|---|---|---|---|
| `code/data/transactions.csv` | 2 | The 500 synthetic transactions | Copied into the `import` folder |
| `load-and-call.cypher` | 2 | Loads the transactions and makes the first call | Plugin |
| `notebooks/02_load_and_call.ipynb` | 2 | The same statements, one per cell | Plugin, Jupyter |
| `failure-demos.cypher` | 3 | Triggers each kind of failure and shows the error | Plugin, data loaded |
| `notebooks/03_failure_demos.ipynb` | 3 | The same statements, one per cell | Plugin, data loaded, Jupyter |
| `notebooks/04_decide_tests.ipynb` | 4 | Live tests of `jev.decide`, including retries and timings | Plugin, key, Jupyter |
| `notebooks/results_decide.json` | 4 | Saved results of the last run | Written by the notebook |
| `key-demos.cypher` | 5 | Shows where the key is sent and where it isn't | Plugin, key |
| `notebooks/05_key_demos.ipynb` | 5 | The same statements, one per cell | Plugin, key, Jupyter |
| `notebooks/05_decide_tests.ipynb` | 5 | Live tests, now including the key rules | Plugin, key, Jupyter |
| `notebooks/results_decide.json` | 5 | Saved results of the last run | Written by the notebook |
| `run-all-500.cypher` | 6 | Decides all 500 transactions with `jev.decideAll` and stores `Decision` nodes | Plugin, key, data loaded |
| `notebooks/06_run_all_500.ipynb` | 6 | The same statements, one per cell | Plugin, key, data loaded, Jupyter |
| `notebooks/06_decideall_tests.ipynb` | 6 | Live tests of the batch procedure and the concurrency sweep | Plugin, key, Jupyter |
| `notebooks/results_decideall.json` | 6 | Saved results of the last run | Written by the notebook |
| `run-local-500.cypher` | 7 | Decides all 500 transactions with the local model and stores `LocalDecision` nodes | Plugin, Ollama, the chapter 6 decisions |
| `notebooks/07_run_local_500.ipynb` | 7 | The same statements, one per cell | Plugin, Ollama, the chapter 6 decisions, Jupyter |
| `notebooks/07_decide_tests.ipynb` | 7 | Live tests against Jev and the local model | Plugin, key, Ollama, Jupyter |
| `notebooks/07_decideall_tests.ipynb` | 7 | Live batch tests against Jev and the local model, with the local sweep | Plugin, key, Ollama, Jupyter |
| `notebooks/results_decide.json` and `notebooks/results_decideall.json` | 7 | Saved results of the last runs | Written by the notebooks |

The results files are overwritten each time you run their notebook. The Java source is in each folder's `src` directory and the chapters explain it as it grows.

In the next chapter we build the first version of the function and make the call from the preview on a real transaction.
