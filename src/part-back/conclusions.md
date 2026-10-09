# Conclusions

## What We Built

We started with a graph that could answer questions about structure and none about judgment. We ended with this:

- **A function, `jev.decide`,** that takes a node's properties and some questions and returns an answer with probabilities, from Cypher.
- **A procedure, `jev.decideAll`,** that does the same for many nodes at once, on a bounded pool, with results in input order.
- **A suite of 68 tests** that need no network and two notebooks that check the real thing.
- **500 stored decisions** from a hosted service and 500 from a local model, as nodes linked to the transactions they describe, ready to query.

Each chapter contributed one lesson:

| Chapter | The lesson |
|---|---|
| 2. A First Working Function | Neo4j's types stop at the boundary, so convert them on the way in and out. |
| 3. Failures as Data | A call that can fail should return its failure as a value, so one bad node can't stop a query. |
| 4. Testing Without a Network | Put a seam where the network and the clock meet and the hard cases become fast tests. |
| 5. Keeping the Key Safe | A credential goes to the host the parser reports, not the one the text suggests. |
| 6. Many at Once | Concurrency has to be bounded, ordered and measured. |
| 7. Running It Locally | Swapping the model is cheap, but it changes the judgment and not just the speed. |

## What the Lessons Share

Look back over the six and a pattern shows. Each one moves a decision out of a place where it would be made by accident and into a place where it's made on purpose.

The type rules decide what crosses the boundary instead of letting a stray `Integer` through. The error prefixes decide what a failure looks like instead of leaving it to whatever exception arrives. The seams decide what a test controls. The host rule decides where the key goes, so no caller has to get that right every time. The pool decides how much runs at once. And the options decide which model answers.

None of these is clever. Each is a small piece of structure put where a mistake would otherwise be easy and most were added because we'd hit the problem or seen it coming while testing.

## What the Numbers Did and Didn't Show

We measured a fair amount and it's worth being plain about what it supports.

**The hosted service is close to repeatable, not exactly.** We ran the full set of 500 twice. The totals barely moved, 267 flags in the first run and 268 in the second, but the five least-sure flags were different transactions each time, all at 0.50 or 0.51. The transactions near the line move around and the ones far from it don't. If you store decisions, treat them as a snapshot.

**Concurrency paid off against the hosted service.** In the sweep of 20 items, 4 workers gave 3.77 times the speed of 1, 8 gave 7.01 times and 16 gave 10.47 times. The full run of 500 took 15,465 milliseconds at a concurrency of 8, where one at a time would have taken about two minutes by our estimate. Against the local model the picture was different: the best speedup was 2.79 times at 4 workers and 8 and 16 workers were slower than 4.

**A small local model is a different judge.** With the same prompt and the same eight properties, the small local model (`tev1:0.8b`, served by Ollama) flagged 20 of the 500 and caught 16 of the 189 transactions labeled as fraud, where the hosted service flagged 268 and caught 171. The two agreed on 252 of 500. We haven't tried other prompts or models, so this shows how far apart the two setups are with this one prompt. It doesn't say what a local model can do. Its probabilities also came in only 15 distinct values, which we noted and didn't explain.

**Timings from a repeated request mislead.** The local sweep sent the same 40 small requests again and again and ran at about 5 milliseconds per item. The full run, with 500 different transactions, ran at about 110 milliseconds per item at the same concurrency. We haven't found out why and it's a reminder to time the real workload.

**One thing we can't explain.** At several levels of local concurrency, the same batch took one of two speeds, one about twice the other, with no pattern we could see.

**The data is synthetic.** Each transaction was generated from a scenario with a latent probability of fraud and the label was drawn from it, so some of the fraud in the data was never certain. The results say how the code and the models behave on this data. They aren't evidence about real fraud.

## What We Left Out

A few things the build doesn't do, which you'd want to think about before relying on it:

- **There's no caching.** The same state sent twice makes two calls.
- **Retries aren't coordinated.** A rate limit on one call is retried with the same backoff as in a single call and the calls in a batch don't share a throttle.
- **A batch returns when it's finished.** The procedure sends its results when every call is done, so a large batch against a slow endpoint takes that long before anything comes back.
- **A batch has a ceiling.** Each call takes at most 10,000 items and a bigger graph goes in slices.
- **It needs Neo4j Desktop.** Custom plugins aren't available on Aura.
- **We tried one prompt and two models.** The hosted service and `tev1:0.8b` through Ollama, with the setup in chapter 7. Better instructions or a different model might do much better and we didn't look.

## Where to Go Next

Because the pieces are small, there are several ways to take this further without rewriting any of it.

**Try other models and prompts.** The `url` and `model` options are the only things that change. Run a new model over the 500 transactions, store the answers beside the others and use the queries from chapter 7 to compare them.

**Use the confidence to decide who looks.** The stored probabilities give you a margin, the gap between `p_flag` and `p_pass`. A small margin marks a decision that deserves a person's eyes. Where to draw the line is yours to choose and the 0.8 in chapter 6 was only an illustration.

**Ask more than one question.** We used a single choice question. The function also takes score questions and its validator checks both shapes before any call is made.

**Keep the habits.** Even if you never use this function, the habits in these chapters apply to any custom code that calls out of Neo4j: return failures as values, put a seam where the outside world meets your code, decide in code where a credential can go and measure the real workload.

## A Last Word

Putting a model call inside a database turned out to be less about the model than about everything around it: the types, the failures, the key, the tests and the numbers. The decision is one line of Cypher. What makes that line safe to run over a whole graph is the rest of the book.
