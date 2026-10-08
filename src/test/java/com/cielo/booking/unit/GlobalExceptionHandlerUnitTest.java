package com.cielo.booking.unit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cielo.booking.controller.EventController;
import com.cielo.booking.exception.GlobalExceptionHandler;
import com.cielo.booking.service.EventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerUnitTest {

    private MockMvc mockMvc;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger handlerLogger;

    @BeforeEach
    void setup() {
        EventService events = mock(EventService.class);
        when(events.get(any())).thenThrow(new IllegalStateException("boom"));

        mockMvc = MockMvcBuilders
                .standaloneSetup(new EventController(events))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        handlerLogger.addAppender(logAppender);
    }

    @Test
    void unexpectedExceptionMustBecomeInternalErrorBodyAndBeLogged() throws Exception {
        try {
            mockMvc.perform(get("/events/{id}", UUID.randomUUID()))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.message").value("Unexpected error."))
                    .andExpect(jsonPath("$.timestamp").isNotEmpty());

            List<ILoggingEvent> errors = logAppender.list.stream()
                    .filter(e -> e.getLevel() == Level.ERROR)
                    .toList();

            assertEquals(1, errors.size(), "the 500 must be logged once as ERROR");

            ILoggingEvent event = errors.get(0);
            assertNotNull(event.getThrowableProxy(), "stack trace must be logged");
            assertEquals(IllegalStateException.class.getName(),
                    event.getThrowableProxy().getClassName());
        } finally {
            handlerLogger.detachAppender(logAppender);
        }
    }
}
