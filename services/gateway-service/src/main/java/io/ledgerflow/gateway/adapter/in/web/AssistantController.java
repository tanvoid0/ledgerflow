package io.ledgerflow.gateway.adapter.in.web;

import io.ledgerflow.gateway.application.LedgerAssistant;
import io.ledgerflow.gateway.application.LedgerAssistant.Proposal;
import io.ledgerflow.llm.LlmException;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** POST a question, get the answer streamed back as Server-Sent Events. Read-only; the token is the caller's own. */
@RestController
class AssistantController {

    private final LedgerAssistant assistant;
    private final AsyncTaskExecutor executor;

    AssistantController(LedgerAssistant assistant, AsyncTaskExecutor applicationTaskExecutor) {
        this.assistant = assistant;
        this.executor = applicationTaskExecutor;
    }

    record Ask(String question) {}

    @PostMapping(value = "/api/v1/insights", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter ask(@RequestBody Ask ask, @RequestHeader(HttpHeaders.AUTHORIZATION) String bearer) {
        if (ask == null || !StringUtils.hasText(ask.question())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question is required");
        var sse = new SseEmitter(120_000L);                 // the LLM is the slow part; 2 min covers a reasoning model under load
        executor.execute(() -> assistant.answer(ask.question(), bearer, sse));
        return sse;
    }

    /**
     * Phase 4: propose (never fire) a refund. Read-only - returns a proposal for a human to approve; firing is a
     * separate ledger-write call to /api/v1/orders/{id}/refund. Synchronous JSON, not SSE: the client renders it.
     */
    @PostMapping(value = "/api/v1/insights/propose", produces = MediaType.APPLICATION_JSON_VALUE)
    Proposal propose(@RequestBody Ask ask, @RequestHeader(HttpHeaders.AUTHORIZATION) String bearer) {
        if (ask == null || !StringUtils.hasText(ask.question())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question is required");
        try {
            return assistant.propose(ask.question(), bearer);
        } catch (LlmException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "the assistant is unavailable right now");
        }
    }
}
