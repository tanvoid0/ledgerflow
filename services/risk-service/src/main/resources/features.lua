-- One account's features, updated atomically as one event arrives. The z-score uses the mean and
-- variance from BEFORE this amount, so the payment being scored never inflates its own baseline.
-- KEYS[1] processed:<eventId>          the Inbox already dedupes; this guard only protects these
--                                      counters from a rolled-back transaction retrying the event
-- KEYS[2] acct:<accountId>:t           sorted set of this account's recent event timestamps (millis)
-- KEYS[3] acct:<accountId>:stats       hash: n, sum, sumsq of amountMinor, for the running z-score
-- KEYS[4] acct:<accountId>:benef       set of beneficiaries this account has paid before
-- ARGV[1] now (millis, the envelope's occurredAt)
-- ARGV[2] amountMinor
-- ARGV[3] beneficiary, or '' when the payment has none
if redis.call('SET', KEYS[1], '1', 'NX', 'EX', 604800) == false then return false end

-- the member is KEYS[1] (unique per event), not ARGV[1]: two events in the same millisecond would
-- otherwise share a member and ZADD would coalesce them into one entry instead of counting both
redis.call('ZADD', KEYS[2], ARGV[1], KEYS[1])
redis.call('ZREMRANGEBYSCORE', KEYS[2], 0, ARGV[1] - 60000)
local count = redis.call('ZCARD', KEYS[2])
redis.call('EXPIRE', KEYS[2], 300)

local n = tonumber(redis.call('HGET', KEYS[3], 'n')) or 0
local sum = tonumber(redis.call('HGET', KEYS[3], 'sum')) or 0
local sumsq = tonumber(redis.call('HGET', KEYS[3], 'sumsq')) or 0
local x = tonumber(ARGV[2])
local z = 0
if n >= 2 then
  local mean = sum / n
  local variance = sumsq / n - mean * mean
  if variance > 0 then z = (x - mean) / math.sqrt(variance) end
end
redis.call('HINCRBYFLOAT', KEYS[3], 'n', 1)
redis.call('HINCRBYFLOAT', KEYS[3], 'sum', x)
redis.call('HINCRBYFLOAT', KEYS[3], 'sumsq', x * x)
redis.call('EXPIRE', KEYS[3], 604800)

local isNew = 0
if ARGV[3] ~= '' then
  isNew = redis.call('SADD', KEYS[4], ARGV[3])
  redis.call('EXPIRE', KEYS[4], 604800)
end

-- z as a string: Redis truncates a Lua number reply to an integer, and the decimal is the point
return {count, tostring(z), isNew}
