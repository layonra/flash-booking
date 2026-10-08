package com.cielo.booking.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.OffsetDateTime;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(NotFoundException ex, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, ex, request);
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ErrorResponse> conflict(ConflictException ex, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, ex, request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> validation(
            MethodArgumentNotValidException ex,
            HttpServletRequest request) {

        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));

        return ResponseEntity.badRequest().body(
                new ErrorResponse(
                        "VALIDATION_ERROR",
                        message,
                        OffsetDateTime.now(),
                        request.getRequestURI()
                )
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> unreadable(
            HttpMessageNotReadableException ex,
            HttpServletRequest request) {

        return ResponseEntity.badRequest().body(
                new ErrorResponse(
                        "MALFORMED_REQUEST",
                        "Malformed request body.",
                        OffsetDateTime.now(),
                        request.getRequestURI()
                )
        );
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> typeMismatch(
            MethodArgumentTypeMismatchException ex,
            HttpServletRequest request) {

        return ResponseEntity.badRequest().body(
                new ErrorResponse(
                        "INVALID_PARAMETER",
                        "Invalid value for parameter '" + ex.getName() + "'.",
                        OffsetDateTime.now(),
                        request.getRequestURI()
                )
        );
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> noResource(NoResourceFoundException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                new ErrorResponse(
                        "NOT_FOUND",
                        "Resource not found.",
                        OffsetDateTime.now(),
                        request.getRequestURI()
                )
        );
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> generic(Exception ex, HttpServletRequest request) {
        LOG.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);

        return ResponseEntity.internalServerError().body(
                new ErrorResponse(
                        "INTERNAL_ERROR",
                        "Unexpected error.",
                        OffsetDateTime.now(),
                        request.getRequestURI()
                )
        );
    }

    private ResponseEntity<ErrorResponse> response(
            HttpStatus status,
            ApiException ex,
            HttpServletRequest request) {

        return ResponseEntity.status(status).body(
                new ErrorResponse(
                        ex.getCode(),
                        ex.getMessage(),
                        OffsetDateTime.now(),
                        request.getRequestURI()
                )
        );
    }

    public record ErrorResponse(
            String code,
            String message,
            OffsetDateTime timestamp,
            String path
    ) {
    }
}
