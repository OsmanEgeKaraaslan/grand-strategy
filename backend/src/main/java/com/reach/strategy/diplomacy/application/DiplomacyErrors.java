package com.reach.strategy.diplomacy.application;

import java.util.List;
import java.util.stream.Collectors;

import com.reach.strategy.diplomacy.rules.Diplomacy;
import com.reach.strategy.diplomacy.rules.PeaceTerms;
import com.reach.strategy.platform.web.ApiException;

import org.springframework.http.HttpStatus;

/** Error responses of the diplomacy use cases (codes documented in docs/API_DESIGN.md §2.4f). */
final class DiplomacyErrors {

	private DiplomacyErrors() {
	}

	static ApiException notAvailable() {
		return new ApiException(HttpStatus.CONFLICT, "DIPLOMACY_NOT_AVAILABLE",
				"This game's ruleset has no diplomacy: every nation is permanently at war.");
	}

	static ApiException nationNotFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "NATION_NOT_FOUND", "Nation not found in this game.");
	}

	static ApiException war(Diplomacy.WarBlocker blocker) {
		return switch (blocker) {
			case SELF -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "CANNOT_TARGET_SELF",
					"A nation cannot declare war on itself.");
			case UNKNOWN_NATION -> nationNotFound();
			case ALREADY_AT_WAR -> new ApiException(HttpStatus.CONFLICT, "ALREADY_AT_WAR",
					"You are already at war with this nation.");
			case ALLIED -> new ApiException(HttpStatus.CONFLICT, "ALLIED_NATION",
					"You are allied with this nation; end the alliance before declaring war.");
			case TRUCE -> new ApiException(HttpStatus.CONFLICT, "TRUCE_IN_EFFECT",
					"A truce from your last peace treaty with this nation is still in effect.");
			case FIGHTING_TOGETHER -> new ApiException(HttpStatus.CONFLICT, "FIGHTING_TOGETHER",
					"This nation fights on your side of a war; you cannot declare war on it.");
		};
	}

	static ApiException alliance(Diplomacy.AllianceBlocker blocker) {
		return switch (blocker) {
			case SELF -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "CANNOT_TARGET_SELF",
					"A nation cannot ally with itself.");
			case UNKNOWN_NATION -> nationNotFound();
			case AT_WAR -> new ApiException(HttpStatus.CONFLICT, "AT_WAR",
					"You are at war with this nation; make peace before proposing an alliance.");
			case ALREADY_ALLIED -> new ApiException(HttpStatus.CONFLICT, "ALREADY_ALLIED",
					"You are already allied with this nation.");
			case ALREADY_PROPOSED -> new ApiException(HttpStatus.CONFLICT, "ALLIANCE_ALREADY_PROPOSED",
					"An alliance proposal between you and this nation is already waiting for an answer.");
			case ENEMY_OF_ALLY -> new ApiException(HttpStatus.CONFLICT, "ALLIANCE_CONFLICT",
					"This alliance would bind you to a nation at war with one of your allies (or the reverse).");
		};
	}

	static ApiException allianceNotFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "ALLIANCE_NOT_FOUND", "Alliance not found in this game.");
	}

	static ApiException warNotFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "WAR_NOT_FOUND", "War not found in this game.");
	}

	static ApiException proposalNotFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "PEACE_PROPOSAL_NOT_FOUND", "Peace proposal not found.");
	}

	static ApiException unprocessable(String code, String detail) {
		return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, detail);
	}

	static ApiException conflict(String code, String detail) {
		return new ApiException(HttpStatus.CONFLICT, code, detail);
	}

	static ApiException forbidden(String code, String detail) {
		return new ApiException(HttpStatus.FORBIDDEN, code, detail);
	}

	static ApiException invalidTerm(String detail) {
		return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TERM", detail);
	}

	static ApiException termsInvalid(List<PeaceTerms.Violation> violations, boolean atAcceptance) {
		String list = violations.stream()
			.map(v -> (v.term() < 0 ? "proposal" : "term " + (v.term() + 1)) + ": " + v.code())
			.collect(Collectors.joining("; "));
		return atAcceptance
				? new ApiException(HttpStatus.CONFLICT, "PEACE_TERMS_NO_LONGER_VALID",
						"The proposal can no longer be settled as it stands (" + list + "). Reject it or make a counter-proposal.")
				: new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "PEACE_TERMS_INVALID",
						"The peace terms are not valid (" + list + ").");
	}

}
