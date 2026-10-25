package api.joinify.identity;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class TokenRepository {

	enum Type { VERIFY_EMAIL, RESET_PASSWORD }

	private final JdbcClient jdbc;

	TokenRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	void insert(String tokenHash, String userId, Type type, Duration ttl) {
		jdbc.sql("INSERT INTO auth_tokens (token_hash, user_id, type, expires_at) VALUES (:hash, :user, :type, :exp)")
				.param("hash", tokenHash).param("user", userId).param("type", type.name())
				.param("exp", LocalDateTime.now(ZoneOffset.UTC).plus(ttl)).update();
	}

	/** Consume el token si es válido (existe, del tipo correcto, sin usar y sin expirar) y devuelve el id del usuario. */
	Optional<String> consume(String tokenHash, Type type) {
		LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
		int updated = jdbc.sql("UPDATE auth_tokens SET used_at = :now WHERE token_hash = :hash AND type = :type AND used_at IS NULL AND expires_at > :now")
				.param("now", now).param("hash", tokenHash).param("type", type.name()).update();
		if (updated == 0) {
			return Optional.empty();
		}
		return jdbc.sql("SELECT user_id FROM auth_tokens WHERE token_hash = :hash").param("hash", tokenHash)
				.query(String.class).optional();
	}
}
