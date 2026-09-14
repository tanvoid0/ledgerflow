package io.ledgerflow.ledger.adapter.in.web;

import io.ledgerflow.ledger.domain.model.UnknownWalletException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.net.URI;
import java.util.List;

/** RFC 9457 problem details, same shape as account-service. */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final String PROBLEMS = "https://ledgerflow.io/problems/";

    @ExceptionHandler(UnknownWalletException.class)
    ProblemDetail onUnknownWallet(UnknownWalletException e) {
        return problem(HttpStatus.BAD_REQUEST, "Unknown wallet", "unknown-wallet", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail onInvalid(MethodArgumentNotValidException e) {
        return validation(e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage()).toList());
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ProblemDetail onInvalid(HandlerMethodValidationException e) {
        return validation(e.getParameterValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream()
                        .map(err -> (err instanceof FieldError f ? f.getField()
                                : r.getMethodParameter().getParameterName()) + ": " + err.getDefaultMessage()))
                .toList());
    }

    private static ProblemDetail validation(List<String> errors) {
        var p = problem(HttpStatus.BAD_REQUEST, "Validation failed", "validation", "Request is invalid");
        p.setProperty("errors", errors);
        return p;
    }

    static ProblemDetail problem(HttpStatus status, String title, String type, String detail) {
        var p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setTitle(title);
        p.setType(URI.create(PROBLEMS + type));
        return p;
    }
}
