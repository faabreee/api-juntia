package api.joinify.notification;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class NotificationRepository {

	record Row(String id, String type, String recipientEmail, String recipientUserId, String organizationId, String eventId,
			String params, String dedupeKey, String status, int attempts, LocalDateTime nextAttemptAt, String subject,
			String lastError, LocalDateTime createdAt, LocalDateTime sentAt) {
	}

	private static final String COLUMNS = "id, type, recipient_email, recipient_user_id, organization_id, event_id, params, dedupe_key, status, attempts, next_attempt_at, subject, last_error, created_at, sent_at";

	private final JdbcClient jdbc;

	NotificationRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	private static Row map(ResultSet rs, int i) throws SQLException {
		return new Row(rs.getString("id"), rs.getString("type"), rs.getString("recipient_email"), rs.getString("recipient_user_id"),
				rs.getString("organization_id"), rs.getString("event_id"), rs.getString("params"), rs.getString("dedupe_key"),
				rs.getString("status"), rs.getInt("attempts"), rs.getObject("next_attempt_at", LocalDateTime.class),
				rs.getString("subject"), rs.getString("last_error"), rs.getObject("created_at", LocalDateTime.class),
				rs.getObject("sent_at", LocalDateTime.class));
	}

	/** Lanza DuplicateKeyException si ya existe un aviso con esa clave. */
	String insert(String type, String email, String userId, String organizationId, String eventId, String params, String dedupeKey,
			LocalDateTime sendAfter, LocalDateTime now) {
		String id = UUID.randomUUID().toString();
		jdbc.sql("INSERT INTO notifications (id, type, recipient_email, recipient_user_id, organization_id, event_id, params, dedupe_key, status, attempts, next_attempt_at, created_at) VALUES (:id, :type, :email, :user, :org, :event, :params, :key, 'PENDING', 0, :after, :now)")
				.param("id", id).param("type", type).param("email", email).param("user", userId).param("org", organizationId)
				.param("event", eventId).param("params", params).param("key", dedupeKey).param("after", sendAfter).param("now", now).update();
		return id;
	}

	Optional<Row> find(String id) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM notifications WHERE id = :id").param("id", id).query(NotificationRepository::map).optional();
	}

	/** Pendientes cuya hora llegó, y envíos "alquilados" cuyo alquiler venció (quien los tomó cayó antes de terminar). */
	List<String> findDueIds(LocalDateTime now, int limit) {
		return jdbc.sql("SELECT id FROM notifications WHERE (status = 'PENDING' AND next_attempt_at <= :now) OR (status = 'SENDING' AND lease_until < :now) ORDER BY next_attempt_at, id LIMIT :limit")
				.param("now", now).param("limit", limit).query(String.class).list();
	}

	/** Toma el envío con un UPDATE condicional: de varios despachadores a la vez, solo uno lo consigue. */
	boolean claim(String id, LocalDateTime now, LocalDateTime leaseUntil) {
		return jdbc.sql("UPDATE notifications SET status = 'SENDING', attempts = attempts + 1, lease_until = :lease WHERE id = :id AND ((status = 'PENDING' AND next_attempt_at <= :now) OR (status = 'SENDING' AND lease_until < :now))")
				.param("lease", leaseUntil).param("id", id).param("now", now).update() == 1;
	}

	void markSent(String id, String subject, LocalDateTime now) {
		jdbc.sql("UPDATE notifications SET status = 'SENT', subject = :subject, sent_at = :now, lease_until = NULL, last_error = NULL WHERE id = :id AND status = 'SENDING'")
				.param("subject", subject).param("now", now).param("id", id).update();
	}

	void markSkipped(String id, String reason) {
		jdbc.sql("UPDATE notifications SET status = 'SKIPPED', lease_until = NULL, last_error = :reason WHERE id = :id AND status = 'SENDING'")
				.param("reason", truncate(reason)).param("id", id).update();
	}

	void markRetry(String id, LocalDateTime nextAttempt, String error) {
		jdbc.sql("UPDATE notifications SET status = 'PENDING', next_attempt_at = :next, lease_until = NULL, last_error = :error WHERE id = :id AND status = 'SENDING'")
				.param("next", nextAttempt).param("error", truncate(error)).param("id", id).update();
	}

	void markFailed(String id, String error) {
		jdbc.sql("UPDATE notifications SET status = 'FAILED', lease_until = NULL, last_error = :error WHERE id = :id AND status = 'SENDING'")
				.param("error", truncate(error)).param("id", id).update();
	}

	/** Un organizador reactiva un envío fallido: vuelve a la cola con los intentos en cero. */
	boolean requeueFailed(String id, LocalDateTime now) {
		return jdbc.sql("UPDATE notifications SET status = 'PENDING', attempts = 0, next_attempt_at = :now, last_error = NULL WHERE id = :id AND status = 'FAILED'")
				.param("now", now).param("id", id).update() == 1;
	}

	List<Row> findByEvent(String organizationId, String eventId, String status, int limit) {
		String sql = "SELECT " + COLUMNS + " FROM notifications WHERE organization_id = :org AND event_id = :event"
				+ (status != null ? " AND status = :status" : "") + " ORDER BY created_at DESC, id LIMIT :limit";
		var spec = jdbc.sql(sql).param("org", organizationId).param("event", eventId).param("limit", limit);
		if (status != null) {
			spec = spec.param("status", status);
		}
		return spec.query(NotificationRepository::map).list();
	}

	long countRecent(String type, String dedupePrefix, LocalDateTime since) {
		return jdbc.sql("SELECT COUNT(*) FROM notifications WHERE type = :type AND dedupe_key LIKE :prefix AND created_at >= :since")
				.param("type", type).param("prefix", dedupePrefix + "%").param("since", since).query(Long.class).single();
	}

	private static String truncate(String s) {
		return s == null ? null : s.length() > 500 ? s.substring(0, 500) : s;
	}
}
