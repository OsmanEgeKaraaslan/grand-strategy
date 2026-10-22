package com.reach.strategy.social.application;

import com.reach.strategy.platform.web.ApiException;

import org.springframework.http.HttpStatus;

/** Error responses of the social module (codes documented in docs/API_DESIGN.md §2.8 and §3). */
final class SocialErrors {

	private SocialErrors() {
	}

	/** Also for computer accounts: they have no profile and cannot be addressed. */
	static ApiException playerNotFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "PLAYER_NOT_FOUND", "No player has that username.");
	}

	static ApiException notFound(String what) {
		return new ApiException(HttpStatus.NOT_FOUND, what + "_NOT_FOUND", "Not found.");
	}

	static ApiException conflict(String code, String detail) {
		return new ApiException(HttpStatus.CONFLICT, code, detail);
	}

	static ApiException unprocessable(String code, String detail) {
		return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, detail);
	}

}
