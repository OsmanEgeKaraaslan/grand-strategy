package com.reach.strategy.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UsernameTest {

	@Test
	void inputIsTrimmedAndLowercasedSoHandlesAreCaseInsensitive() {
		assertThat(Username.parse("  Aldric_The_Bold ")).contains(new Username("aldric_the_bold"));
		assertThat(Username.normalize("ALDRIC")).isEqualTo("aldric");
	}

	@ParameterizedTest
	@ValueSource(strings = { "ab", "a23456789012345678901", "1abc", "_abc", "al dric", "al-dric", "äldric",
			"<script>", "a'b", "" })
	void rejectsTooShortTooLongAndForeignCharacters(String raw) {
		assertThat(Username.check(raw)).contains(Username.Problem.INVALID);
		assertThat(Username.parse(raw)).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "admin", "System", "Administrator", "npc", "player_12ab", "Sundered_Reach" })
	void rejectsReservedNamesAndTheMigratedPrefix(String raw) {
		assertThat(Username.check(raw)).contains(Username.Problem.RESERVED);
	}

	@Test
	void acceptsTheBoundaryLengths() {
		assertThat(Username.check("abc")).isEmpty();
		assertThat(Username.check("a1234567890123456789")).isEmpty();
	}

	@Test
	void suggestsAHandleFromADisplayName() {
		assertThat(Username.suggestedFrom("Aldric the Bold")).contains(new Username("aldric_the_bold"));
		assertThat(Username.suggestedFrom("Commander-7KQ2XM")).contains(new Username("commander_7kq2xm"));
		assertThat(Username.suggestedFrom("李明")).isEmpty();
		assertThat(Username.suggestedFrom("Admin")).isEmpty();
	}

	@Test
	void generatedHandlesAreValidAndNotReserved() {
		for (int i = 0; i < 50; i++) {
			assertThat(Username.check(Username.generated().value())).isEmpty();
		}
	}

	@Test
	void theRecordItselfRefusesInvalidValues() {
		assertThatThrownBy(() -> new Username("Upper")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void emblemsAreAFixedList() {
		assertThat(Emblem.parse("Tower")).contains(Emblem.TOWER);
		assertThat(Emblem.parse("https://example.com/me.png")).isEmpty();
	}

}
