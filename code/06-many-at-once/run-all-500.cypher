// Chapter 6: decide all 500 transactions in one call and store the answers.
// Run the statements one at a time, so you can see each result. Each statement ends with a semicolon.
// Needs the 500 transactions loaded in chapter 2 and TYPESAFE_API_KEY set.
// The run makes 500 calls to the hosted service.

// 1. Is the procedure installed? Expect one row: jev.decideAll
SHOW PROCEDURES YIELD name WHERE name STARTS WITH 'jev' RETURN name;

// 2. Decide every transaction that has no decision yet, 8 calls at a time, and store
//    each answer as a Decision node linked to its transaction. Rows that failed are
//    left out, and statement 3 shows how many are still missing. Neo4j shows how
//    long the statement took.
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
RETURN count(d) AS decisions_stored;

// 3. How many transactions have a decision? Expect 500. If some calls failed, run
//    statement 2 again: it only picks up transactions without a decision.
MATCH (t:Transaction)
OPTIONAL MATCH (t)-[:HAS_DECISION]->(d:Decision)
RETURN count(DISTINCT t) AS transactions, count(d) AS with_decision;

// 4. The split: how many were flagged and how many passed?
MATCH (:Transaction)-[:HAS_DECISION]->(d:Decision)
RETURN d.choice AS choice, count(*) AS n
ORDER BY n DESC;

// 5. The five flagged transactions Jev was least sure about. Start there when reviewing.
MATCH (t:Transaction)-[:HAS_DECISION]->(d:Decision)
WHERE d.choice = 'Flag'
RETURN t.txn_id, t.amount, d.p_flag
ORDER BY d.p_flag ASC
LIMIT 5;

// 6. Decisions against the hidden label. is_fraud was never sent to Jev.
MATCH (t:Transaction)-[:HAS_DECISION]->(d:Decision)
RETURN d.choice AS choice, t.is_fraud AS is_fraud, count(*) AS n
ORDER BY choice, is_fraud;

// 7. Passes that might be missed fraud: passed, but labeled fraud, least sure first.
MATCH (t:Transaction)-[:HAS_DECISION]->(d:Decision)
WHERE d.choice = 'Pass' AND t.is_fraud = 1
RETURN t.txn_id, t.scenario, d.p_flag
ORDER BY d.p_flag DESC
LIMIT 5;

// 8. How much could be handled without a person looking at it? Here "confident" is
//    a confidence of 0.8 or more, an illustrative threshold, not a recommendation.
MATCH (:Transaction)-[:HAS_DECISION]->(d:Decision)
RETURN count(*) AS decisions,
       sum(CASE WHEN d.confidence >= 0.8 THEN 1 ELSE 0 END) AS confident,
       round(100.0 * sum(CASE WHEN d.confidence >= 0.8 THEN 1 ELSE 0 END) / count(*), 1) AS percent_confident;

// 9. Reset (optional, leave commented out). To run the whole batch again from scratch,
//    delete only the decisions. The transactions stay. Then run statement 2 again.
// MATCH (d:Decision) DETACH DELETE d;
