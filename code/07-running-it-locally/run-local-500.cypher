// Chapter 7: the same function against a local model.
// Run the statements one at a time, so you can see each result. Each statement ends with a semicolon.
// Before you start: Ollama is running and the model is pulled (ollama pull tev1:0.8b).
// Needs the 500 transactions from chapter 2 and the hosted Decision nodes from chapter 6.
// No key is used and nothing leaves your machine.

// 1. One call to the local model. authenticated is false: the key is only ever sent to Jev.
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
) AS result;

// 2. Decide every transaction locally, 4 calls at a time, and store each answer as a
//    LocalDecision node. The hosted Decision nodes from chapter 6 are left alone.
//    margin is the gap between the two probabilities, so local and hosted confidence
//    can be compared on one scale. Neo4j shows how long the statement took.
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
RETURN count(l) AS local_decisions_stored;

// 3. How many transactions have a local decision? Expect 500. If some calls failed,
//    run statement 2 again: it only picks up transactions without one.
MATCH (t:Transaction)
OPTIONAL MATCH (t)-[:HAS_LOCAL_DECISION]->(l:LocalDecision)
RETURN count(DISTINCT t) AS transactions, count(l) AS with_local_decision;

// 4. The local split: how many were flagged and how many passed?
MATCH (:Transaction)-[:HAS_LOCAL_DECISION]->(l:LocalDecision)
RETURN l.choice AS choice, count(*) AS n
ORDER BY n DESC;

// 5. Local decisions against the hidden label. is_fraud was never sent to the model.
MATCH (t:Transaction)-[:HAS_LOCAL_DECISION]->(l:LocalDecision)
RETURN l.choice AS choice, t.is_fraud AS is_fraud, count(*) AS n
ORDER BY choice, is_fraud;

// 6. Hosted against local: how often do the two agree?
MATCH (t:Transaction)-[:HAS_DECISION]->(h:Decision),
      (t)-[:HAS_LOCAL_DECISION]->(l:LocalDecision)
RETURN h.choice AS hosted, l.choice AS local, count(*) AS n
ORDER BY hosted, local;

// 7. Where they disagree, by scenario.
MATCH (t:Transaction)-[:HAS_DECISION]->(h:Decision),
      (t)-[:HAS_LOCAL_DECISION]->(l:LocalDecision)
WHERE h.choice <> l.choice
RETURN t.scenario AS scenario, count(*) AS disagreements
ORDER BY disagreements DESC;

// 8. Confidence on one scale. The reported confidence means different things (the
//    margin for the hosted service, one minus the normalized entropy locally).
//    The margin computed from the probabilities is comparable.
MATCH (t:Transaction)-[:HAS_DECISION]->(h:Decision),
      (t)-[:HAS_LOCAL_DECISION]->(l:LocalDecision)
RETURN round(avg(h.confidence), 3)                  AS hosted_reported_confidence,
       round(avg(abs(h.p_flag - h.p_pass)), 3)      AS hosted_margin,
       round(avg(l.confidence), 3)                  AS local_reported_confidence,
       round(avg(l.margin), 3)                      AS local_margin;

// 9. Latency per call, as measured inside the function.
MATCH (h:Decision) WITH avg(h.latency_ms) AS hosted_ms
MATCH (l:LocalDecision)
RETURN round(hosted_ms, 1) AS hosted_avg_ms, round(avg(l.latency_ms), 1) AS local_avg_ms;

// 10. Reset (optional, leave commented out). To run the local batch again from scratch,
//     delete only the local decisions. The hosted decisions and the transactions stay.
// MATCH (l:LocalDecision) DETACH DELETE l;
