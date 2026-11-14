package com.azentio.doppel.common;

import java.util.Comparator;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Converts errors to 400 responses. Rejected values are deliberately NOT echoed back,
 * so an invalid registrant account number never appears in a response or log.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        List<ApiError.FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> new ApiError.FieldError(e.getField(), e.getDefaultMessage()))
                .sorted(Comparator.comparing(ApiError.FieldError::field))
                .toList();
        return badRequest("VALIDATION_FAILED", "Request validation failed", errors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex) {
        return badRequest("MALFORMED_REQUEST", "Request body is missing or not valid JSON", List.of());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return badRequest("VALIDATION_FAILED", "Invalid request parameter",
                List.of(new ApiError.FieldError(ex.getName(), "has an invalid value")));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex) {
        return badRequest("BAD_REQUEST", ex.getMessage(), List.of());
    }

    private ResponseEntity<ApiError> badRequest(String code, String message, List<ApiError.FieldError> errors) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(HttpStatus.BAD_REQUEST.value(), code, message, errors));
    }
}
