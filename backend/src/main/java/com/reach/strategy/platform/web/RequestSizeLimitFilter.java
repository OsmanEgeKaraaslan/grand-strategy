package com.reach.strategy.platform.web;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bounds request bodies (Phase 15B). Every API request body is a small JSON document, so anything
 * larger than {@code strategy.web.max-request-body} is refused with 413 before it is read; a body
 * without a declared length is cut off at the same size while it is read.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

	private final long maxBytes;
	private final JsonMapper jsonMapper;

	public RequestSizeLimitFilter(@Value("${strategy.web.max-request-body:64KB}") DataSize maxRequestBody,
			JsonMapper jsonMapper) {
		this.maxBytes = maxRequestBody.toBytes();
		this.jsonMapper = jsonMapper;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (request.getContentLengthLong() > maxBytes) {
			response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
			response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
			jsonMapper.writeValue(response.getOutputStream(), ApiProblems.create(HttpStatus.CONTENT_TOO_LARGE,
					"PAYLOAD_TOO_LARGE", "Content Too Large", "The request body is larger than " + maxBytes + " bytes.",
					request));
			return;
		}
		chain.doFilter(request.getContentLengthLong() < 0 ? new Bounded(request, maxBytes) : request, response);
	}

	/** Fails the read (so the request ends as malformed) once more than the limit has arrived. */
	private static final class Bounded extends HttpServletRequestWrapper {

		private final long maxBytes;

		Bounded(HttpServletRequest request, long maxBytes) {
			super(request);
			this.maxBytes = maxBytes;
		}

		@Override
		public ServletInputStream getInputStream() throws IOException {
			ServletInputStream in = super.getInputStream();
			return new ServletInputStream() {

				private long read;

				@Override
				public int read() throws IOException {
					int b = in.read();
					if (b >= 0 && ++read > maxBytes) {
						throw new IOException("Request body exceeds " + maxBytes + " bytes");
					}
					return b;
				}

				@Override
				public int read(byte[] buffer, int offset, int length) throws IOException {
					int n = in.read(buffer, offset, length);
					if (n > 0 && (read += n) > maxBytes) {
						throw new IOException("Request body exceeds " + maxBytes + " bytes");
					}
					return n;
				}

				@Override
				public boolean isFinished() {
					return in.isFinished();
				}

				@Override
				public boolean isReady() {
					return in.isReady();
				}

				@Override
				public void setReadListener(ReadListener listener) {
					in.setReadListener(listener);
				}

			};
		}

	}

}
