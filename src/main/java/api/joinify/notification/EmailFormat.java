package api.joinify.notification;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Currency;
import java.util.Locale;

/** Formatos de importes y fechas para los correos (español, en la zona horaria del evento). */
public final class EmailFormat {

	private static final Locale ES = Locale.forLanguageTag("es-PE");

	private EmailFormat() {
	}

	/** Importe en la unidad menor ("8000", PEN) -> "S/ 80.00". El 0 se muestra como "Gratis". */
	public static String money(long minor, String currencyCode) {
		if (minor == 0) {
			return "Gratis";
		}
		Currency currency = Currency.getInstance(currencyCode);
		NumberFormat format = NumberFormat.getCurrencyInstance(ES);
		format.setCurrency(currency);
		int digits = Math.max(currency.getDefaultFractionDigits(), 0);
		// setCurrency no cambia los decimales del formato: se fijan según la moneda (el yen no lleva, el sol lleva dos).
		format.setMinimumFractionDigits(digits);
		format.setMaximumFractionDigits(digits);
		return format.format(BigDecimal.valueOf(minor, digits));
	}

	/** "sábado, 15 de noviembre de 2026, 17:56" en la zona del evento. */
	public static String dateTime(Instant instant, String timezone) {
		return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.FULL, FormatStyle.SHORT).withLocale(ES).withZone(ZoneId.of(timezone))
				.format(instant);
	}

	public static String time(Instant instant, String timezone) {
		return DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(ES).withZone(ZoneId.of(timezone)).format(instant);
	}

	/** Un solo día: "sábado, 15 de noviembre de 2026". */
	public static String date(Instant instant, String timezone) {
		return DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(ES).withZone(ZoneId.of(timezone)).format(instant);
	}
}
