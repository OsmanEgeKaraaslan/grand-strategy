package com.reach.strategy.platform.health;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;

import com.reach.strategy.support.ApiSliceTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@ApiSliceTest(HealthController.class)
class HealthControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	Clock clock;

	@Test
	void returnsStructuredStatusWithoutAuthentication() throws Exception {
		when(clock.instant()).thenReturn(Instant.parse("2026-10-17T12:00:00Z"));

		mockMvc.perform(get("/api/v1/health"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.status").value("UP"))
			.andExpect(jsonPath("$.service").value("strategy-backend"))
			.andExpect(jsonPath("$.version").isString())
			.andExpect(jsonPath("$.timestamp").value("2026-10-17T12:00:00Z"));
	}

}
