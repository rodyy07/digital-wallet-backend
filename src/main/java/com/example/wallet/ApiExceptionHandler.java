package com.example.wallet;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(WalletService.WalletException.class)
    ResponseEntity<?> wallet(WalletService.WalletException e, HttpServletRequest request) {
        return ResponseEntity.status(e.status).body(Map.of("code", e.code, "message", e.getMessage(),
                "request_id", requestId()));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> validation(Exception e, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(Map.of("code","VALIDATION_ERROR","message","Invalid request body","request_id",requestId()));
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> conflict(Exception e, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code","CONFLICT","message","A unique or database constraint was violated","request_id",requestId()));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception e, HttpServletRequest request) {
        return ResponseEntity.status(500).body(Map.of("code","INTERNAL_ERROR","message","Unexpected server error","request_id",requestId()));
    }
    private String requestId() { return UUID.randomUUID().toString(); }
}
