package api.joinify.ticket;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * Código corto de un boleto, para buscarlo a mano en puerta. Ocho caracteres de un alfabeto sin símbolos
 * ambiguos (sin 0/O, 1/I/L ni U): 30^8 combinaciones, mostrados como {@code ABCD-EFGH}.
 */
public final class ShortCode {

	static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ";
	static final int LENGTH = 8;
	private static final SecureRandom RANDOM = new SecureRandom();

	private ShortCode() {
	}

	public static String generate() {
		StringBuilder code = new StringBuilder(LENGTH);
		for (int i = 0; i < LENGTH; i++) {
			code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
		}
		return code.toString();
	}

	/** {@code ABCDEFGH} -> {@code ABCD-EFGH}. */
	public static String format(String code) {
		return code.substring(0, 4) + "-" + code.substring(4);
	}

	/** Lo que escribe una persona (minúsculas, espacios, guion) -> forma guardada. Vacío si no es un código válido. */
	public static java.util.Optional<String> normalize(String input) {
		if (input == null) {
			return java.util.Optional.empty();
		}
		String clean = input.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s-]", "");
		if (clean.length() != LENGTH || !clean.chars().allMatch(c -> ALPHABET.indexOf(c) >= 0)) {
			return java.util.Optional.empty();
		}
		return java.util.Optional.of(clean);
	}
}
