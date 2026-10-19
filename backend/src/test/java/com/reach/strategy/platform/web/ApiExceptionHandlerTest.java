package com.reach.strategy.platform.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import com.reach.strategy.support.ApiSliceTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Error-response conventions (docs/API_DESIGN.md §3), exercised through a test-only controller.
 * Requests are authenticated with a mock user because every non-health path is protected.
 */
@ApiSliceTest(ApiExceptionHandlerTest.ThrowingController.class)
@Import(ApiExceptionHandlerTest.ThrowingController.class)
@WithMockUser
class ApiExceptionHandlerTest {

	@Autowired
	MockMvc mockMvc;

	@Test
	void apiExceptionKeepsItsStatusAndCode() throws Exception {
		mockMvc.perform(get("/test/insufficient-gold"))
			.andExpect(status().isUnprocessableContent())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("INSUFFICIENT_GOLD"))
			.andExpect(jsonPath("$.detail").value("Treasury is too low."))
			.andExpect(jsonPath("$.instance").value("/test/insufficient-gold"))
			.andExpect(jsonPath("$.requestId").isNotEmpty());
	}

	@Test
	void validationErrorsListEveryInvalidField() throws Exception {
		mockMvc.perform(post("/test/validated").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" \"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("name"))
			.andExpect(jsonPath("$.errors[0].message").isNotEmpty());
	}

	@Test
	void malformedJsonIsABadRequestWithCode() throws Exception {
		mockMvc.perform(post("/test/validated").contentType(MediaType.APPLICATION_JSON).content("{not json"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("BAD_REQUEST"))
			.andExpect(jsonPath("$.requestId").isNotEmpty());
	}

	@Test
	void unsupportedMethodGetsSpringProblemWithCode() throws Exception {
		mockMvc.perform(post("/test/insufficient-gold"))
			.andExpect(status().isMethodNotAllowed())
			.andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
	}

	@Test
	void unexpectedExceptionsDoNotLeakDetails() throws Exception {
		mockMvc.perform(get("/test/bug"))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
			.andExpect(content().string(not(containsString("secret internal state"))));
	}

	@RestController
	static class ThrowingController {

		@GetMapping("/test/insufficient-gold")
		void insufficientGold() {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INSUFFICIENT_GOLD", "Treasury is too low.");
		}

		@PostMapping("/test/validated")
		void validated(@Valid @RequestBody NamedRequest request) {
		}

		@GetMapping("/test/bug")
		void bug() {
			throw new IllegalStateException("secret internal state");
		}

	}

	record NamedRequest(@NotBlank String name) {
	}

}
