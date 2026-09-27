package io.ledgerflow.ticketing.adapter.in.web;

import io.ledgerflow.ticketing.application.Ticketing;
import io.ledgerflow.ticketing.application.Ticketing.EventDetail;
import io.ledgerflow.ticketing.application.Ticketing.EventSummary;
import io.ledgerflow.ticketing.application.Ticketing.Order;
import io.ledgerflow.ticketing.config.TicketingProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
class EventsController {

    private final Ticketing ticketing;
    private final TicketingProperties props;

    /** organiser goes to payment-service as the beneficiary, which caps it at 64. */
    record CreateEventRequest(@NotBlank String title, String subtitle, @NotBlank String venue, @NotBlank String city, String category,
                              @NotNull Instant startsAt, @NotBlank @Size(max = 64) String organiser, @Positive long priceMinor,
                              @NotBlank @Size(min = 3, max = 3) String currency, @Positive int rows, @Positive int seatsPerRow) {}

    /** The body never carries a seat count past max-seats: not a validation the client can spoof its way past. */
    record HoldRequest(@NotEmpty List<@NotBlank String> seats) {}

    @GetMapping
    List<EventSummary> events() {
        return ticketing.events();
    }

    @GetMapping("/{id}")
    EventDetail event(@PathVariable UUID id) {
        return ticketing.event(id).orElseThrow(() -> new ErrorResponseException(HttpStatus.NOT_FOUND));
    }

    @PostMapping
    @PreAuthorize("hasRole('ledger-write')")
    @ResponseStatus(HttpStatus.CREATED)
    EventSummary create(@Valid @RequestBody CreateEventRequest req) {
        return ticketing.createEvent(req.title(), req.subtitle(), req.venue(), req.city(), req.category(), req.startsAt(),
                req.organiser(), req.priceMinor(), req.currency(), req.rows(), req.seatsPerRow());
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('ledger-write')")
    void cancel(@PathVariable UUID id) {
        ticketing.cancelEvent(id);
    }

    /** accountId is always the caller's own subject; the body carries seats only. */
    @PostMapping("/{id}/holds")
    @PreAuthorize("hasRole('customer')")
    @ResponseStatus(HttpStatus.CREATED)
    Order hold(@PathVariable UUID id, @Valid @RequestBody HoldRequest req, @AuthenticationPrincipal Jwt jwt) {
        if (req.seats().size() > props.maxSeats()) throw new ErrorResponseException(HttpStatus.BAD_REQUEST);
        var orderId = ticketing.hold(id, UUID.fromString(jwt.getSubject()), jwt.getClaimAsString("preferred_username"), req.seats());
        return ticketing.order(orderId).orElseThrow();
    }
}
