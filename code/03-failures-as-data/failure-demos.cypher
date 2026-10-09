// Chapter 3: failures as data.
// Run the statements one at a time, so you can see each result. Each statement ends with a semicolon.
// Statements 1 to 3 never reach the network. Statement 4 makes three live calls.
// Needs the 500 transactions loaded in chapter 2 and TYPESAFE_API_KEY set.

// 1. Wrong criteria shape: a choice question needs a map, not an array.
RETURN jev.decide(
  {amount: 120.5, merchant_category: 'grocery'},
  {decision: {
     type: 'choice',
     instructions: 'Should this transaction be flagged or passed?',
     criteria: ['Flag', 'Pass']
  }}
) AS result;

// 2. A typo in the options: "modle" instead of "model".
RETURN jev.decide(
  {amount: 120.5, merchant_category: 'grocery'},
  {decision: {
     type: 'choice',
     instructions: 'Should this transaction be flagged or passed?',
     criteria: {Flag: 'Potential fraud.', Pass: 'Likely legitimate.'}
  }},
  {modle: 'jev-latest'}
) AS result;

// 3. A blank state.
RETURN jev.decide(
  '   ',
  {decision: {
     type: 'choice',
     instructions: 'Should this transaction be flagged or passed?',
     criteria: {Flag: 'Potential fraud.', Pass: 'Likely legitimate.'}
  }}
) AS result;

// 4. Four transactions, one that doesn't exist, so its state is null. The bad one
//    returns an error and the other three still return answers. Without the
//    never-throw rule this whole query would fail.
UNWIND ['T0001', 'T0002', 'BAD', 'T0003'] AS id
OPTIONAL MATCH (t:Transaction {txn_id: id})
WITH id, jev.decide(
  CASE WHEN t IS NULL THEN null ELSE
    t {.amount, .merchant_category, .distance_from_home_km, .hour_of_day,
       .account_age_days, .prior_flags, .txns_last_24h, .typical_monthly_spend} END,
  {decision: {
     type: 'choice',
     instructions: 'Based on all available signals, should this transaction be flagged for review or passed through?',
     criteria: {
       Flag: 'Potential fraud. Send for review.',
       Pass: 'Likely legitimate. Process normally.'
     }
  }}
) AS result
RETURN id,
       result.answers.decision.choice AS choice,
       result.error_message AS error_message,
       result.metadata.attempts AS attempts;
