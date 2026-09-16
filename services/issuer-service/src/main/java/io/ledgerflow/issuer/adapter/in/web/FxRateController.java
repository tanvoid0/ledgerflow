package io.ledgerflow.issuer.adapter.in.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stub FX feed: GBP needs no uplift, everything else costs 25 basis points. The fault counter is here
 * so a test can arm a run of 503s on demand — a flaky dependency you cannot reproduce is one you cannot
 * test against.
 */
@RestController
@RequestMapping("/api/v1/fx")
class FxRateController {

    private final AtomicInteger faults = new AtomicInteger();

    @GetMapping("/{currency}")
    int upliftBps(@PathVariable String currency) {
        if (faults.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "fx feed is warming up");
        }
        return "GBP".equals(currency) ? 0 : 25;
    }

    @PostMapping("/faults")
    void arm(@RequestParam int count) {
        faults.set(count);
    }
}
