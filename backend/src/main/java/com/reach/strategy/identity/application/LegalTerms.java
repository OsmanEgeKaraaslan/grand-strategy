package com.reach.strategy.identity.application;

import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The legal document versions players currently accept (Phase 15A, docs/DATA_PRIVACY_INVENTORY.md).
 * Raising a version in configuration asks every player to accept again on their next visit; when
 * {@code requireAcceptance} is on, the API refuses everything but the account's own endpoints until
 * they have.
 */
@Component
public class LegalTerms {

	private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9._-]{1,32}");

	private final String termsVersion;
	private final String privacyVersion;
	private final boolean requireAcceptance;

	LegalTerms(@Value("${strategy.legal.terms-version}") String termsVersion,
			@Value("${strategy.legal.privacy-version}") String privacyVersion,
			@Value("${strategy.legal.require-acceptance:true}") boolean requireAcceptance) {
		if (!VERSION.matcher(termsVersion).matches() || !VERSION.matcher(privacyVersion).matches()) {
			throw new IllegalArgumentException("Legal document versions have 1–32 letters, digits, '.', '_' or '-'");
		}
		this.termsVersion = termsVersion;
		this.privacyVersion = privacyVersion;
		this.requireAcceptance = requireAcceptance;
	}

	public String termsVersion() {
		return termsVersion;
	}

	public String privacyVersion() {
		return privacyVersion;
	}

	public boolean requireAcceptance() {
		return requireAcceptance;
	}

	boolean isCurrent(String acceptedTerms, String acceptedPrivacy) {
		return termsVersion.equals(acceptedTerms) && privacyVersion.equals(acceptedPrivacy);
	}

}
