package com.reach.strategy.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DisplayNameTest {

	@Test
	void inputIsTrimmedAndInnerWhitespaceCollapsed() {
		assertThat(DisplayName.parse("  Queen   of  Varn ")).contains(new DisplayName("Queen of Varn"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "Aldric", "Ósk", "kael_2", "North-Wind", "Lord of Ostra" })
	void acceptsReasonableNames(String name) {
		assertThat(DisplayName.parse(name)).isPresent();
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "ab", "a-name-that-is-way-too-long", "<script>", "admin@x", "semi;colon" })
	void rejectsInvalidNames(String name) {
		assertThat(DisplayName.parse(name)).isEmpty();
	}

	@Test
	void generatedNamesAreValid() {
		assertThat(DisplayName.generated().value()).matches("Commander-[A-Z2-9]{6}");
	}

}
