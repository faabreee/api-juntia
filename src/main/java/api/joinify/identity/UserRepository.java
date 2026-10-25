package api.joinify.identity;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class UserRepository {

	private final JdbcClient jdbc;

	UserRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	Optional<AuthUser> findByEmail(String email) {
		return jdbc.sql("SELECT id, email, password_hash, display_name, email_verified_at FROM users WHERE email = :email")
				.param("email", email)
				.query((rs, i) -> new AuthUser(rs.getString("id"), rs.getString("email"), rs.getString("password_hash"),
						rs.getString("display_name"), rs.getObject("email_verified_at") != null))
				.optional();
	}

	boolean existsByEmail(String email) {
		return jdbc.sql("SELECT COUNT(*) FROM users WHERE email = :email").param("email", email)
				.query(Long.class).single() > 0;
	}

	String insert(String email, String passwordHash, String displayName) {
		String id = UUID.randomUUID().toString();
		jdbc.sql("INSERT INTO users (id, email, password_hash, display_name, created_at) VALUES (:id, :email, :hash, :name, :now)")
				.param("id", id).param("email", email).param("hash", passwordHash).param("name", displayName)
				.param("now", LocalDateTime.now(ZoneOffset.UTC))
				.update();
		return id;
	}

	void markEmailVerified(String userId) {
		jdbc.sql("UPDATE users SET email_verified_at = COALESCE(email_verified_at, :now) WHERE id = :id")
				.param("now", LocalDateTime.now(ZoneOffset.UTC)).param("id", userId).update();
	}

	void updatePassword(String userId, String passwordHash) {
		jdbc.sql("UPDATE users SET password_hash = :hash WHERE id = :id")
				.param("hash", passwordHash).param("id", userId).update();
	}
}
