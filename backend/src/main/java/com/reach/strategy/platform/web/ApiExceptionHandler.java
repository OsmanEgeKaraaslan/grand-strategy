package com.reach.strategy.platform.web;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Central translation of exceptions into RFC 9457 problem responses (docs/API_DESIGN.md §3).
 * Spring MVC's built-in exceptions are handled by the superclass and then decorated with a
 * {@code code} and {@code requestId}; unexpected exceptions never leak details to the client.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ProblemDetail> handleApiException(ApiException ex, HttpServletRequest request) {
		return problem(ex.status(), ex.code(), ex.getMessage(), request);
	}

	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
		return problem(HttpStatus.FORBIDDEN, "FORBIDDEN", "You are not allowed to perform this action.", request);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
		log.error("Unhandled exception for {} {}", request.getMethod(), request.getRequestURI(), ex);
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
				"An unexpected error occurred. Quote the requestId when reporting it.", request);
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		HttpServletRequest servletRequest = ((ServletWebRequest) request).getRequest();
		ProblemDetail body = ApiProblems.create(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Validation failed",
				"The request contains invalid fields.", servletRequest);
		List<FieldViolation> errors = ex.getBindingResult()
			.getFieldErrors()
			.stream()
			.map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
			.toList();
		body.setProperty("errors", errors);
		return handleExceptionInternal(ex, body, headers, HttpStatus.BAD_REQUEST, request);
	}

	/** Decorates every problem produced by the superclass (404, 405, 415, malformed JSON, ...). */
	@Override
	protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers, HttpStatusCode statusCode,
			WebRequest request) {
		if (body instanceof ProblemDetail problem && request instanceof ServletWebRequest servletWebRequest) {
			if (problem.getProperties() == null || !problem.getProperties().containsKey(ApiProblems.CODE)) {
				problem.setProperty(ApiProblems.CODE, defaultCode(statusCode));
			}
			ApiProblems.decorate(problem, servletWebRequest.getRequest());
		}
		return super.createResponseEntity(body, headers, statusCode, request);
	}

	private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail,
			HttpServletRequest request) {
		return ResponseEntity.status(status)
			.body(ApiProblems.create(status, code, status.getReasonPhrase(), detail, request));
	}

	private static String defaultCode(HttpStatusCode statusCode) {
		HttpStatus status = HttpStatus.resolve(statusCode.value());
		return status != null ? status.name() : "HTTP_" + statusCode.value();
	}

	record FieldViolation(String field, String message) {
	}

}
