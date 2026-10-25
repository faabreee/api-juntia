package api.joinify.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class AuthService {

	private static final Duration VERIFY_TTL = Duration.ofHours(24);
	private static final Duration RESET_TTL = Duration.ofHours(1);
	private static final SecureRandom RANDOM = new SecureRandom();

	private final UserRepository users;
	private final TokenRepository tokens;
	private final PasswordEncoder passwordEncoder;
	private final AuthMailer mailer;
	private final JdbcClient jdbc;

	AuthService(UserRepository users, TokenRepository tokens, PasswordEncoder passwordEncoder, AuthMailer mailer,
			JdbcClient jdbc) {
		this.users = users;
		this.tokens = tokens;
		this.passwordEncoder = passwordEncoder;
		this.mailer = mailer;
		this.jdbc = jdbc;
	}

	static String normalize(String email) {
		return email.trim().toLowerCase(Locale.ROOT);
	}

	@Transactional
	void register(String rawEmail, String password, String displayName) {
		String email = normalize(rawEmail);
		if (users.existsByEmail(email)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "El correo ya está registrado");
		}
		String userId = users.insert(email, passwordEncoder.encode(password), displayName.trim());
		String token = issueToken(userId, TokenRepository.Type.VERIFY_EMAIL, VERIFY_TTL);
		mailer.sendVerification(email, displayName.trim(), token);
	}

	@Transactional
	void verifyEmail(String token) {
		String userId = tokens.consume(hash(token), TokenRepository.Type.VERIFY_EMAIL)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enlace inválido o expirado"));
		users.markEmailVerified(userId);
	}

	/** Respuesta siempre silenciosa para no revelar qué correos existen. */
	@Transactional
	void resendVerification(String rawEmail) {
		users.findByEmail(normalize(rawEmail)).filter(u -> !u.isEmailVerified()).ifPresent(u -> {
			String token = issueToken(u.getId(), TokenRepository.Type.VERIFY_EMAIL, VERIFY_TTL);
			mailer.sendVerification(u.getEmail(), u.getDisplayName(), token);
		});
	}

	@Transactional
	void forgotPassword(String rawEmail) {
		users.findByEmail(normalize(rawEmail)).ifPresent(u -> {
			String token = issueToken(u.getId(), TokenRepository.Type.RESET_PASSWORD, RESET_TTL);
			mailer.sendPasswordReset(u.getEmail(), u.getDisplayName(), token);
		});
	}

	@Transactional
	void resetPassword(String token, String newPassword) {
		String userId = tokens.consume(hash(token), TokenRepository.Type.RESET_PASSWORD)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enlace inválido o expirado"));
		users.updatePassword(userId, passwordEncoder.encode(newPassword));
		// Una contraseña nueva cierra todas las sesiones abiertas de esa cuenta.
		jdbc.sql("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME = (SELECT email FROM users WHERE id = :id)")
				.param("id", userId).update();
	}

	private String issueToken(String userId, TokenRepository.Type type, Duration ttl) {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		tokens.insert(hash(token), userId, type, ttl);
		return token;
	}

	private static String hash(String token) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
