package com.awesome.payment.controller.exception;

import com.awesome.payment.dto.ErrorDto;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.HashMap;
import java.util.Map;

/**
 * API error bodies, identical to the backend's: {@code ErrorDto} or field-to-message maps.
 * Messages never echo request values, so card numbers cannot leak through errors or logs.
 */
@RestControllerAdvice
public class GlobalExceptionHandlerController {

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<ErrorDto> handleCustomException(CustomException ex) {
        return ResponseEntity
                .status(ex.getHttpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorDto(ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String key = error instanceof FieldError fieldError ? fieldError.getField() : error.getObjectName();
            errors.put(key, error.getDefaultMessage());
        });
        return ResponseEntity
                .badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(errors);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgumentException(IllegalArgumentException ex) {
        Map<String, String> errors = new HashMap<>();
        errors.put("error", ex.getMessage());
        return ResponseEntity
                .badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(errors);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorDto> handleAccessDeniedException(AccessDeniedException ex) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorDto("Access denied"));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorDto> handleAuthenticationException(AuthenticationException ex) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorDto("Unauthorized"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorDto> handleUnreadableBody(HttpMessageNotReadableException ex) {
        return error(HttpStatus.BAD_REQUEST, "Malformed JSON request");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorDto> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return error(HttpStatus.BAD_REQUEST, "Invalid value for " + ex.getName());
    }

    /** Two requests raced for the same Idempotency-Key or refund requestId. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorDto> handleDataIntegrity(DataIntegrityViolationException ex) {
        return error(HttpStatus.CONFLICT, "Conflicting concurrent request");
    }

    /** The payment changed under a concurrent request; retrying sees the new state. */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorDto> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "Payment was updated concurrently, retry");
    }

    private static ResponseEntity<ErrorDto> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(new ErrorDto(message));
    }
}
