package com.looptrip;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(PlanValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(PlanValidationException exception) {
        return ResponseEntity.badRequest()
                .body(new ApiErrorResponse("BAD_REQUEST", exception.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableRequest() {
        return ResponseEntity.badRequest()
                .body(new ApiErrorResponse("BAD_REQUEST", "request body is missing or malformed"));
    }

    @ExceptionHandler(ModelCallException.class)
    public ResponseEntity<ApiErrorResponse> handleModelCall(ModelCallException exception) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ApiErrorResponse("MODEL_CALL_FAILED", exception.getMessage()));
    }
}
