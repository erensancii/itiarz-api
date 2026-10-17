package com.itiarz.common;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Renders every error as RFC 9457 problem+json with {@code code} and {@code traceId} (spec §8).
 * Also used by the security entry points and filters, which run outside the DispatcherServlet.
 */
@RestControllerAdvice
public class ProblemDetailsHandler {
    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsHandler.class);

    private final ObjectMapper objectMapper;

    public ProblemDetailsHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ProblemDetail> domain(DomainException ex, HttpServletRequest request) {
        return respond(problem(ex.status(), ex.code(), ex.getMessage(), request));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> invalid(MethodArgumentNotValidException ex, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.VALIDATION,
                "Bazı alanlar geçersiz. Lütfen işaretli alanları düzeltip tekrar deneyin.", request);
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(),
                        "message", String.valueOf(error.getDefaultMessage())))
                .toList();
        problem.setProperty("errors", errors);
        return respond(problem);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return respond(problem(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.VALIDATION,
                "İstek gövdesi okunamadı. JSON, enum ve UTC tarih biçimlerini kontrol edin.", request));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> optimisticLock(ObjectOptimisticLockingFailureException ex,
                                                 HttpServletRequest request) {
        return respond(problem(HttpStatus.CONFLICT, ErrorCode.CONFLICT,
                "Kayıt siz çalışırken başka biri tarafından değiştirildi. Sayfayı yenileyip tekrar deneyin.",
                request));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> integrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("Data integrity violation [traceId={}]", TraceIdFilter.traceId(request), ex);
        return respond(problem(HttpStatus.CONFLICT, ErrorCode.CONFLICT,
                "Kayıt mevcut verilerle çakışıyor. Bilgileri kontrol edip tekrar deneyin.", request));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> accessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return respond(forbidden(request));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception ex, HttpServletRequest request) {
        if (ex instanceof ErrorResponse framework) {
            HttpStatusCode status = framework.getStatusCode();
            ErrorCode code = status.value() == 404 ? ErrorCode.NOT_FOUND : null;
            HttpStatus resolved = HttpStatus.resolve(status.value());
            return respond(problem(resolved == null ? HttpStatus.BAD_REQUEST : resolved, code, null, request));
        }
        log.error("Unhandled exception [traceId={}]", TraceIdFilter.traceId(request), ex);
        return respond(problem(HttpStatus.INTERNAL_SERVER_ERROR, null,
                "Beklenmeyen bir hata oluştu. Destek koduyla birlikte bize ulaşabilirsiniz.", request));
    }

    public ProblemDetail problem(HttpStatus status, ErrorCode code, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle(status.getReasonPhrase());
        if (detail != null) {
            problem.setDetail(detail);
        }
        if (code != null) {
            problem.setProperty("code", code.name());
        }
        problem.setProperty("traceId", TraceIdFilter.traceId(request));
        return problem;
    }

    public ProblemDetail unauthorized(HttpServletRequest request) {
        return problem(HttpStatus.UNAUTHORIZED, null,
                "Oturumunuz bulunamadı veya süresi doldu. Lütfen yeniden giriş yapın.", request);
    }

    public ProblemDetail forbidden(HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, null,
                "Bu işlem için yetkiniz yok. Gerekirse şirket yöneticinizden yetki isteyin.", request);
    }

    /** Writes a problem directly to the servlet response (filters, entry points). */
    public void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", problem.getType().toString());
        body.put("title", problem.getTitle());
        body.put("status", problem.getStatus());
        if (problem.getDetail() != null) {
            body.put("detail", problem.getDetail());
        }
        if (problem.getProperties() != null) {
            body.putAll(problem.getProperties());
        }
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
