package io.ledgerflow.risk.application;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One account's behaviour, kept in Redis and nowhere else: how many payments it just made, how far
 * this amount sits from its own history, whether this beneficiary is new to it. Disposable, like
 * balance-service's read model, because it is rebuilt one event at a time as the topic replays.
 */
@Component
@RequiredArgsConstructor
public class FeatureWindow {

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> FEATURES = RedisScript.of(new ClassPathResource("features.lua"), List.class);

    private final StringRedisTemplate redis;

    public record Features(int countLastMinute, double amountZScore, boolean newBeneficiary) {}

    /**
     * Empty on replay: the guard inside the script only protects the counters from a rolled-back
     * transaction retrying the event, the Inbox already dedupes the work itself.
     */
    public Optional<Features> observe(String eventId, UUID accountId, long amountMinor, String beneficiary, long now) {
        var keys = List.of("processed:" + eventId, tKey(accountId), statsKey(accountId), benefKey(accountId));
        var result = redis.execute(FEATURES, keys, String.valueOf(now), String.valueOf(amountMinor), beneficiary == null ? "" : beneficiary);
        // a script that returns Lua false at the top comes back as a one-element [null], not a null reply
        if (result == null || result.get(0) == null) return Optional.empty();
        var count = ((Long) result.get(0)).intValue();
        var z = Double.parseDouble((String) result.get(1));
        var isNew = ((Long) result.get(2)) == 1;
        return Optional.of(new Features(count, z, isNew));
    }

    private static String tKey(UUID accountId) {
        return "acct:" + accountId + ":t";
    }

    private static String statsKey(UUID accountId) {
        return "acct:" + accountId + ":stats";
    }

    private static String benefKey(UUID accountId) {
        return "acct:" + accountId + ":benef";
    }
}
