-- Atomically record a flagged VPA for one registrant and count distinct flagged VPAs in the window.
-- KEYS[1] = doppel:campaign:{registrantRef}   (sorted set: member = VPA, score = request timestamp in ms)
-- ARGV[1] = request timestamp (ms)  ARGV[2] = window (ms)  ARGV[3] = VPA (lower-cased)
local key = KEYS[1]
local ts = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local member = ARGV[3]
local window_start = ts - window

-- drop entries that fell out of the window of this request
redis.call('ZREMRANGEBYSCORE', key, '-inf', '(' .. window_start)

-- add the VPA; a VPA seen again keeps its latest timestamp (distinct count does not change)
local current = redis.call('ZSCORE', key, member)
if (not current) or tonumber(current) < ts then
    redis.call('ZADD', key, ts, member)
end

local count = redis.call('ZCOUNT', key, window_start, '+inf')

-- idle registrants leave no keys behind
redis.call('PEXPIRE', key, window)
return count
