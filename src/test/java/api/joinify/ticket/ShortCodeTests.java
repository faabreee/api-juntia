package api.joinify.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ShortCodeTests {

	@Test
	void generatedCodes_useOnlyUnambiguousCharacters() {
		for (int i = 0; i < 2_000; i++) {
			String code = ShortCode.generate();
			assertThat(code).hasSize(ShortCode.LENGTH).matches("[2-9A-HJKMNP-TV-Z]{8}");
			assertThat(code).doesNotContainAnyWhitespaces().doesNotContain("0", "1", "I", "L", "O", "U");
		}
	}

	@Test
	void generatedCodes_doNotRepeat() {
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < 2_000; i++) {
			assertThat(seen.add(ShortCode.generate())).isTrue();
		}
	}

	@Test
	void format_andNormalize_areInverse() {
		assertThat(ShortCode.format("ABCDEFGH")).isEqualTo("ABCD-EFGH");
		assertThat(ShortCode.normalize("abcd-efgh")).contains("ABCDEFGH");
		assertThat(ShortCode.normalize("  ABCD EFGH ")).contains("ABCDEFGH");
		assertThat(ShortCode.normalize("ABCDEFGH")).contains("ABCDEFGH");
	}

	@Test
	void normalize_rejectsAnythingThatIsNotACode() {
		for (String bad : new String[] { null, "", "ABCD", "ABCD-EFGH-J", "ABCD-EFG0", "ABCD-EFGI", "ABCD-EFG!", "ÁBCD-EFGH" }) {
			assertThat(ShortCode.normalize(bad)).as("entrada: %s", bad).isEmpty();
		}
	}
}
