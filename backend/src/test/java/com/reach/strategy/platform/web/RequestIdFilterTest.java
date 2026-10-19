package com.reach.strategy.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

import jakarta.servlet.FilterChain;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
class RequestIdFilterTest {

	@Mock
	FilterChain chain;

	final RequestIdFilter filter = new RequestIdFilter();

	@AfterEach
	void clearMdc() {
		MDC.clear();
	}

	@Test
	void reusesAWellFormedIncomingIdForTheWholeRequestAndCleansUp() throws Exception {
		var request = new MockHttpServletRequest("GET", "/api/v1/health");
		request.addHeader(RequestIdFilter.HEADER, "client-abc-12345");
		var response = new MockHttpServletResponse();
		doAnswer(invocation -> {
			assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isEqualTo("client-abc-12345");
			return null;
		}).when(chain).doFilter(any(), any());

		filter.doFilter(request, response, chain);

		verify(chain).doFilter(request, response);
		assertThat(request.getAttribute(RequestIdFilter.ATTRIBUTE)).isEqualTo("client-abc-12345");
		assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("client-abc-12345");
		assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
	}

	@Test
	void generatesAnIdWhenNoneIsSent() throws Exception {
		var request = new MockHttpServletRequest("GET", "/");
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, chain);

		assertThat(response.getHeader(RequestIdFilter.HEADER)).matches("[0-9a-f-]{36}");
	}

	@ParameterizedTest
	@ValueSource(strings = { "short", "contains spaces in it", "<script>alert(1)</script>",
			"this-request-id-is-far-too-long-to-be-accepted-because-it-exceeds-sixty-four-chars" })
	void replacesUnsafeOrMalformedIds(String unsafe) {
		assertThat(RequestIdFilter.resolveRequestId(unsafe)).isNotEqualTo(unsafe).matches("[0-9a-f-]{36}");
	}

}
