// Chapter 2: load the transactions and make the first call.
// Run the statements one at a time, so you can see each result. Each statement ends with a semicolon.
// Before you start, put transactions.csv in the DBMS import folder.

// 1. Is the function installed? Expect one row: jev.decide
SHOW FUNCTIONS YIELD name WHERE name STARTS WITH 'jev' RETURN name;

// 2. Uniqueness constraint on the transaction ID.
CREATE CONSTRAINT txn_id IF NOT EXISTS
FOR (t:Transaction) REQUIRE t.txn_id IS UNIQUE;

// 3. Load the transactions. MERGE means running this twice creates no duplicates.
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

// 4. How many did we load? Expect 500 transactions.
MATCH (t:Transaction) RETURN count(t) AS transactions;

// 5. The first call. The map projection passes only the eight visible properties,
//    so scenario, true_p and is_fraud never reach Jev. Needs TYPESAFE_API_KEY set.
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
) AS result;
