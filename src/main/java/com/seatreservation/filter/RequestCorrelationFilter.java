package com.seatreservation.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public class RequestCorrelationFilter extends OncePerRequestFilter {

    private static final String REQUEST_ID_HEADER = "X-Request-ID";
    private static final String MDC_REQUEST_ID = "request_id";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {

        String requestId = request.getHeader(REQUEST_ID_HEADER);

        // If client didn't provide an ID, generate one.
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }

        try {
            // Make request ID available to logs during this request.
            MDC.put(MDC_REQUEST_ID, requestId);

            // Also return it to the client.
            response.setHeader(REQUEST_ID_HEADER, requestId);

            filterChain.doFilter(request, response);

        } finally {
            // Tomcat threads are reused, so never leave an old
            // request ID attached to the thread.
            MDC.remove(MDC_REQUEST_ID);
        }
    }
}