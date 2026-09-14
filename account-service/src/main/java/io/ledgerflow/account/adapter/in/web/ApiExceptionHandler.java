package io.ledgerflow.account.adapter.in.web;

import io.ledgerflow.account.application.DuplicateEntryException;
import io.ledgerflow.account.application.InsufficientFundsException;
import io.ledgerflow.account.domain.model.AccountNotFoundException;
import io.ledgerflow.account.domain.model.UnbalancedEntryException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/** RFC 9457 problem details. Callers get something to act on, not a stack trace. */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final String PROBLEMS = "https://ledgerflow.io/problems/";

    @ExceptionHandler(AccountNotFoundException.class)
    ProblemDetail onNotFound(AccountNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Account not found", "account-not-found", e.getMessage());
    }

    @ExceptionHandler(InsufficientFundsException.class)
    ProblemDetail onInsufficientFunds(InsufficientFundsException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Insufficient funds", "insufficient-funds", e.getMessage());
    }

    /**
     * Only reachable when two requests with the same key race past the lookup.
     * The client's correct move is to retry: the lookup wins next time.
     */
    @ExceptionHandler(DuplicateEntryException.class)
    ProblemDetail onDuplicate(DuplicateEntryException e) {
        return problem(HttpStatus.CONFLICT, "Duplicate request", "duplicate-request", e.getMessage());
    }

    @ExceptionHandler({UnbalancedEntryException.class, IllegalArgumentException.class})
    ProblemDetail onBadEntry(RuntimeException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid transfer", "invalid-transfer", e.getMessage());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ProblemDetail onMissingHeader(MissingRequestHeaderException e) {
        return problem(HttpStatus.BAD_REQUEST, "Missing header", "missing-header", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail onInvalid(MethodArgumentNotValidException e) {
        var p = problem(HttpStatus.BAD_REQUEST, "Validation failed", "validation", "Request body is invalid");
        p.setProperty("errors", e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage()).toList());
        return p;
    }

    private static ProblemDetail problem(HttpStatus status, String title, String type, String detail) {
        var p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setTitle(title);
        p.setType(URI.create(PROBLEMS + type));
        return p;
    }
}
