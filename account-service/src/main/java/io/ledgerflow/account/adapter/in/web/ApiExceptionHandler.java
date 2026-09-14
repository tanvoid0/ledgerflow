package io.ledgerflow.account.adapter.in.web;

import io.ledgerflow.account.domain.model.AccountNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/** RFC 9457 problem details. Callers get something to act on, not a stack trace. */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final String PROBLEMS = "https://ledgerflow.io/problems/";

    @ExceptionHandler(AccountNotFoundException.class)
    ProblemDetail onNotFound(AccountNotFoundException e) {
        var p = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        p.setTitle("Account not found");
        p.setType(URI.create(PROBLEMS + "account-not-found"));
        return p;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail onInvalid(MethodArgumentNotValidException e) {
        var p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request body is invalid");
        p.setTitle("Validation failed");
        p.setType(URI.create(PROBLEMS + "validation"));
        p.setProperty("errors", e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage()).toList());
        return p;
    }
}
