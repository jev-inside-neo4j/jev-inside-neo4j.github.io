// Chapter 5: where the API key goes.
// Run the statements one at a time, so you can see each result. Each statement ends with a semicolon.
// Needs the 500 transactions loaded in chapter 2 and TYPESAFE_API_KEY set.

// 1. A normal call to the real endpoint. The key is sent, so metadata.authenticated is true.
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

// 2. A different endpoint: nothing listens on port 9 of your own machine, so the
//    connection is refused. The error names the host and port, one attempt is made
//    and the key is not sent, so authenticated is false.
RETURN jev.decide(
  {amount: 120.5, merchant_category: 'grocery'},
  {decision: {
     type: 'choice',
     instructions: 'Should this transaction be flagged or passed?',
     criteria: {Flag: 'Potential fraud.', Pass: 'Likely legitimate.'}
  }},
  {url: 'http://127.0.0.1:9/v1/systemone', max_retries: 0}
) AS result;

// 3. The same call with the default retries: three attempts with waits of 0.5 s
//    and 1 s between them, so it takes at least 1.5 s.
RETURN jev.decide(
  {amount: 120.5, merchant_category: 'grocery'},
  {decision: {
     type: 'choice',
     instructions: 'Should this transaction be flagged or passed?',
     criteria: {Flag: 'Potential fraud.', Pass: 'Likely legitimate.'}
  }},
  {url: 'http://127.0.0.1:9/v1/systemone'}
) AS result;
