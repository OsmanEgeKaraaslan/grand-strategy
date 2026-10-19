package com.reach.strategy.platform.web;

import org.springframework.http.HttpStatus;

/**
 * Base class for failures that should reach the client as a specific HTTP status and stable
 * error code. Feature modules subclass or throw it; {@link ApiExceptionHandler} renders it.
 */
public class ApiException extends RuntimeException {

	private final HttpStatus status;
	private final String code;

	public ApiException(HttpStatus status, String code, String detail) {
		super(detail);
		this.status = status;
		this.code = code;
	}

	public HttpStatus status() {
		return status;
	}

	public String code() {
		return code;
	}

}
