-- Mark the event and apply every line of it in one atomic step, the Redis form of the inbox transaction:
-- a redelivery finds the mark and changes nothing; a crash mid-way leaves neither the mark nor half the lines.
-- KEYS[1]        processed:<eventId>
-- KEYS[2i],[2i+1] account:<accountId>, done:<token>:<accountId>:<label>   one pair per line
-- ARGV[1]        seconds to remember the event id; then per line: label, currency, balance delta, held delta
if not redis.call('SET', KEYS[1], '1', 'NX', 'EX', ARGV[1]) then return 0 end
for i = 0, (#KEYS - 1) / 2 - 1 do
  local hash, done = KEYS[2 + 2 * i], KEYS[3 + 2 * i]
  local label, currency, balance, held = ARGV[2 + 4 * i], ARGV[3 + 4 * i], ARGV[4 + 4 * i], ARGV[5 + 4 * i]
  redis.call('HINCRBY', hash, label .. ':balance', balance)
  redis.call('HINCRBY', hash, label .. ':held', held)
  redis.call('HSET', hash, label .. ':currency', currency)
  redis.call('SET', done, '1', 'EX', 60)
end
return 1
