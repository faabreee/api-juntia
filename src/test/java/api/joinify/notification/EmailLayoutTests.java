package api.joinify.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class EmailLayoutTests {

	@Test
	void escapes_htmlInUserProvidedText() {
		String evil = "<script>alert('x')</script> & \"quotes\"";

		String html = EmailLayout.wrap("pre", EmailLayout.h1(evil) + EmailLayout.p(evil) + EmailLayout.rows(evil, evil) + EmailLayout.button("https://x.test/?a=1&b=\"2\"", evil));

		assertThat(html).doesNotContain("<script>").doesNotContain("alert('x')");
		assertThat(html).contains("&lt;script&gt;").contains("&amp;").contains("&quot;quotes&quot;");
		assertThat(html).contains("href=\"https://x.test/?a=1&amp;b=&quot;2&quot;\"");
	}

	@Test
	void layout_isACompleteDocument_withBrandAndPreheader() {
		String html = EmailLayout.wrap("Resumen corto", EmailLayout.p("Hola"));

		assertThat(html).startsWith("<!doctype html>").contains("lang=\"es\"").contains("Juntia").contains("Resumen corto").endsWith("</html>");
	}

	@Test
	void money_usesTheCurrencyDecimals_andShowsFreeAsFree() {
		assertThat(EmailFormat.money(8000, "PEN")).contains("80.00");
		assertThat(EmailFormat.money(8050, "PEN")).contains("80.50");
		assertThat(EmailFormat.money(1500, "JPY")).containsPattern("1[,.\\s]?500").doesNotContain(".00"); // sin decimales
		assertThat(EmailFormat.money(0, "PEN")).isEqualTo("Gratis");
	}

	@Test
	void dates_areShownInTheEventTimezone() {
		Instant noonUtc = Instant.parse("2027-03-05T17:00:00Z");

		assertThat(EmailFormat.dateTime(noonUtc, "America/Lima")).contains("12:00").contains("2027");
		assertThat(EmailFormat.dateTime(noonUtc, "Asia/Tokyo")).contains("2:00");
		assertThat(EmailFormat.date(noonUtc, "America/Lima")).doesNotContain(":");
		assertThat(Duration.between(noonUtc, noonUtc.plusSeconds(1)).toSeconds()).isEqualTo(1);
	}

	@Test
	void backoff_growsAndCoversTheDefaultAttempts() {
		var steps = NotificationDispatcher.BACKOFF;

		assertThat(steps).hasSize(5).isSorted();
		assertThat(steps.getFirst()).isEqualTo(Duration.ofMinutes(1));
		assertThat(steps.getLast()).isEqualTo(Duration.ofHours(6));
	}
}
