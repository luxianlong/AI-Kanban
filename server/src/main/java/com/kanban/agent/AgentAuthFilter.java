package com.kanban.agent;

import com.kanban.protocol.AgentProtocol;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The only thing guarding the agent API.
 *
 * <p>Worth being explicit about why this exists in Spring rather than in front of it: the
 * board's password is Vite middleware that sits ahead of the API proxy, and it deliberately
 * lets {@code /api/agent/} through — a worker is not a browser and has no cookie. So on
 * {@code youraikanban.com} there is nothing else in the path. Everything this filter admits
 * can claim work and write results onto the board.
 *
 * <p>Compared in constant time, so the endpoint can't be used to guess the token byte by byte
 * — the same care {@code DelegatedTaskController} takes with its per-task tokens.
 */
@Component
public class AgentAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AgentAuthFilter.class);

    private static final String BEARER = "Bearer ";

    private final AgentProperties props;

    public AgentAuthFilter(AgentProperties props) {
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(AgentProtocol.BASE_PATH);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!props.isEnabled()) {
            // No token configured: refuse rather than run unauthenticated. An agent API with
            // no credential on a public domain is an open door to the board.
            log.warn("Agent API call to {} refused: no agent.token is configured.",
                    request.getRequestURI());
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "The agent API is disabled: this server has no agent.token configured.");
            return;
        }
        if (!authorized(request)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED,
                    "A valid worker token is required.");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean authorized(HttpServletRequest request) {
        String header = request.getHeader(AgentProtocol.AUTH_HEADER);
        if (header == null || !header.startsWith(BEARER)) {
            return false;
        }
        byte[] expected = props.getToken().getBytes(StandardCharsets.UTF_8);
        byte[] presented = header.substring(BEARER.length()).trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, presented);
    }
}
