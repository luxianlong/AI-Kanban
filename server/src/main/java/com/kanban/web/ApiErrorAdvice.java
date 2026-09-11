package com.kanban.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Let a refusal say why it refused.
 *
 * <p>The API declines things in sentences — "Run #12 has already finished, so Step
 * \"Publish\" is not waiting for anything", "The nodes form a cycle" — written into
 * {@link ResponseStatusException}'s reason at the point the code knows which case it is.
 * None of them reached the browser. Boot's error body carries a {@code message} field
 * only when {@code server.error.include-message} is on, and it is off by default, so
 * every one of those sentences was replaced by {@code {"status":409,"error":"Conflict"}}
 * on the way out. The front end has been trying to read them the whole time: {@code
 * cleanError} in Workflows.jsx digs a {@code "message"} out of the body and falls back to
 * the bare status line, and the fallback is all it ever got.
 *
 * <p>This puts the reason back in Boot's own shape, so that reader works unchanged. It is
 * deliberately NOT {@code server.error.include-message: always}, which would do the same
 * job in one line and also publish the message of every exception nobody meant to show —
 * an H2 failure answers with its SQL. The reason of a {@code ResponseStatusException} is
 * different in kind: it exists only because somebody wrote it for the person on the other
 * end. So only that is surfaced, and only when it is actually there — a subclass thrown
 * by the framework itself (a 404 for an unmapped path, say) has no reason, and gets the
 * body it got before, field for field.
 *
 * <p>Scoped to this application's own controllers so nothing else's error rendering
 * changes, and 401s from the agent API's filter never reach here at all — a filter throws
 * outside the dispatcher, where controller advice does not apply.
 */
@RestControllerAdvice(basePackages = "com.kanban")
public class ApiErrorAdvice {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> onResponseStatus(ResponseStatusException ex,
                                                               HttpServletRequest request) {
        HttpStatusCode status = ex.getStatusCode();
        HttpStatus resolved = HttpStatus.resolve(status.value());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", resolved == null ? "" : resolved.getReasonPhrase());
        if (ex.getReason() != null) {
            body.put("message", ex.getReason());
        }
        body.put("path", request.getRequestURI());

        return ResponseEntity.status(status).headers(ex.getHeaders()).body(body);
    }
}
