package com.pushpakraut.docschat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;

/** When the AI provider fails (down, quota used up, bad reply), tell the user clearly instead of a bare 500. */
@RestControllerAdvice
public class AiErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(AiErrorHandler.class);

    @ExceptionHandler({RestClientException.class, IllegalStateException.class})
    ProblemDetail aiFailed(Exception e) {
        log.error("AI service call failed", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                "The AI service is not responding right now. Please try again in a minute.");
    }
}
