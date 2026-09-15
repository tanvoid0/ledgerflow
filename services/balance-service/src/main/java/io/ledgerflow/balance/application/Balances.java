package io.ledgerflow.balance.application;

import io.ledgerflow.events.WalletRef;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The read model: one Redis hash per account, three fields per wallet. Sums only, so the order events
 * arrive in does not matter, and a HoldClosed overtaking its FundsHeld leaves held negative for a moment
 * and right the moment after. The whole thing is disposable: scripts/rebuild-balance.sh.
 */
@Component
@RequiredArgsConstructor
public class Balances {

    private static final RedisScript<Long> APPLY = RedisScript.of(new ClassPathResource("apply.lua"), Long.class);
    // = the topic's retention: a duplicate older than what the broker still holds cannot arrive
    private static final Duration REMEMBER = Duration.ofDays(7);

    private final StringRedisTemplate redis;

    /** How one event moves one wallet. Balance from account's entries, held from ledger's holds. */
    public record Delta(WalletRef wallet, String currency, long balanceMinor, long heldMinor) {}

    /** All the deltas or none, once per event id. False: seen before, nothing changed. */
    public boolean apply(UUID eventId, String token, List<Delta> deltas) {
        var keys = new ArrayList<String>(List.of("processed:" + eventId));
        var args = new ArrayList<String>(List.of(String.valueOf(REMEMBER.toSeconds())));
        for (var d : deltas) {
            keys.add(accountKey(d.wallet().accountId()));
            keys.add(doneKey(token, d.wallet().accountId(), d.wallet().label()));
            args.addAll(List.of(d.wallet().label(), d.currency(), Long.toString(d.balanceMinor()), Long.toString(d.heldMinor())));
        }
        return redis.execute(APPLY, keys, args.toArray()) == 1;
    }

    public List<WalletBalance> account(UUID accountId) {
        var fields = redis.<String, String>opsForHash().entries(accountKey(accountId));
        var byLabel = new TreeMap<String, Map<String, String>>();
        fields.forEach((field, value) -> {
            int at = field.lastIndexOf(':');
            byLabel.computeIfAbsent(field.substring(0, at), k -> new HashMap<>()).put(field.substring(at + 1), value);
        });
        return byLabel.entrySet().stream().map(e -> wallet(e.getKey(), e.getValue())).toList();
    }

    public Optional<WalletBalance> wallet(UUID accountId, String label) {
        var values = redis.<String, String>opsForHash()
                .multiGet(accountKey(accountId), List.of(label + ":balance", label + ":held", label + ":currency"));
        if (values.get(0) == null) return Optional.empty();
        return Optional.of(wallet(label, Map.of("balance", values.get(0), "held", values.get(1), "currency", values.get(2))));
    }

    /** True once an event carrying the token (a write's request id) has been applied to this wallet. ADR 0002. */
    public boolean applied(String token, UUID accountId, String label) {
        return redis.hasKey(doneKey(token, accountId, label));
    }

    private static WalletBalance wallet(String label, Map<String, String> f) {
        return new WalletBalance(label, f.get("currency"), Long.parseLong(f.get("balance")), Long.parseLong(f.get("held")));
    }

    private static String accountKey(UUID accountId) {
        return "account:" + accountId;
    }

    private static String doneKey(String token, UUID accountId, String label) {
        return "done:" + token + ":" + accountId + ":" + label;
    }
}
