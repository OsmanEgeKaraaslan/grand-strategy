package com.reach.strategy.game.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InviteCodeTest {

	@Test
	void generatedCodesUseTheUnambiguousAlphabetAndDoNotRepeat() {
		Set<String> codes = new HashSet<>();
		IntStream.range(0, 10_000).forEach(i -> codes.add(InviteCode.generate().value()));

		assertThat(codes).hasSize(10_000)
			.allSatisfy(code -> assertThat(code).hasSize(12).matches("[A-HJ-NP-Z2-9]+"));
	}

	@Test
	void inputIsNormalizedLeniently() {
		assertThat(InviteCode.parse("  abcd-efgh-jkmn ")).contains(new InviteCode("ABCDEFGHJKMN"));
		assertThat(InviteCode.parse("ABCD EFGH JKMN")).contains(new InviteCode("ABCDEFGHJKMN"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "ABCD", "ABCDEFGHJKMNP", "ABCD-EFGH-JKM0", "ABCD-EFGH-JKMI", "ABCD-EFGH-JK!N" })
	void malformedInputIsNotACode(String input) {
		assertThat(InviteCode.parse(input)).isEmpty();
	}

	@Test
	void nullIsNotACode() {
		assertThat(InviteCode.parse(null)).isEmpty();
	}

	@Test
	void isDisplayedInGroupsOfFour() {
		assertThat(new InviteCode("ABCDEFGHJKMN").formatted()).isEqualTo("ABCD-EFGH-JKMN");
	}

}
