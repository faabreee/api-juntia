package api.joinify.checkin;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class CheckInRepository {

	record DeviceRow(String id, String organizationId, String name, String registeredBy, String registeredByName,
			LocalDateTime createdAt, LocalDateTime lastSeenAt, LocalDateTime revokedAt) {
		boolean revoked() {
			return revokedAt != null;
		}
	}

	record CheckInRow(String id, String scanId, String organizationId, String eventId, String ticketId, String sessionId,
			String deviceId, String result, String reason, String method, LocalDateTime createdAt, LocalDateTime revertedAt) {
	}

	/** Ingreso aceptado y vigente (no revertido): lo que alimenta a AccessPolicy. */
	record EntryRow(String checkInId, String sessionId, LocalDateTime at, String deviceName) {
	}

	record AuditRow(String id, String result, String reason, String method, LocalDateTime createdAt, String ticketId,
			String ticketCode, String ticketTypeName, String attendeeName, String sessionId, String deviceName,
			String validatorName, LocalDateTime revertedAt, String revertedByName, String revertReason) {
	}

	private static final String CHECK_IN_COLUMNS = "id, scan_id, organization_id, event_id, ticket_id, session_id, device_id, result, reason, method, created_at, reverted_at";

	private final JdbcClient jdbc;

	CheckInRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	// ---------- dispositivos ----------

	private static DeviceRow mapDevice(ResultSet rs, int i) throws SQLException {
		return new DeviceRow(rs.getString("id"), rs.getString("organization_id"), rs.getString("name"), rs.getString("registered_by"),
				rs.getString("registered_by_name"), rs.getObject("created_at", LocalDateTime.class),
				rs.getObject("last_seen_at", LocalDateTime.class), rs.getObject("revoked_at", LocalDateTime.class));
	}

	private static final String DEVICE_SELECT = "SELECT d.id, d.organization_id, d.name, d.registered_by, u.display_name AS registered_by_name, d.created_at, d.last_seen_at, d.revoked_at FROM validation_devices d JOIN users u ON u.id = d.registered_by";

	String insertDevice(String organizationId, String name, String registeredBy, LocalDateTime now) {
		String id = UUID.randomUUID().toString();
		jdbc.sql("INSERT INTO validation_devices (id, organization_id, name, registered_by, created_at) VALUES (:id, :org, :name, :by, :now)")
				.param("id", id).param("org", organizationId).param("name", name).param("by", registeredBy).param("now", now).update();
		return id;
	}

	Optional<DeviceRow> findDevice(String id) {
		return jdbc.sql(DEVICE_SELECT + " WHERE d.id = :id").param("id", id).query(CheckInRepository::mapDevice).optional();
	}

	List<DeviceRow> findDevices(String organizationId) {
		return jdbc.sql(DEVICE_SELECT + " WHERE d.organization_id = :org ORDER BY d.revoked_at IS NOT NULL, d.created_at")
				.param("org", organizationId).query(CheckInRepository::mapDevice).list();
	}

	/**
	 * Marca la última actividad del dispositivo. Solo escribe si pasaron más de 30 s desde la última marca, para que
	 * cientos de lecturas por minuto no se disputen una misma fila.
	 */
	void touchDevice(String id, LocalDateTime now) {
		jdbc.sql("UPDATE validation_devices SET last_seen_at = :now WHERE id = :id AND (last_seen_at IS NULL OR last_seen_at < :threshold)")
				.param("now", now).param("threshold", now.minusSeconds(30)).param("id", id).update();
	}

	boolean revokeDevice(String id, String organizationId, String by, LocalDateTime now) {
		return jdbc.sql("UPDATE validation_devices SET revoked_at = :now, revoked_by = :by WHERE id = :id AND organization_id = :org AND revoked_at IS NULL")
				.param("now", now).param("by", by).param("id", id).param("org", organizationId).update() == 1;
	}

	// ---------- check-ins ----------

	private static CheckInRow mapCheckIn(ResultSet rs, int i) throws SQLException {
		return new CheckInRow(rs.getString("id"), rs.getString("scan_id"), rs.getString("organization_id"), rs.getString("event_id"),
				rs.getString("ticket_id"), rs.getString("session_id"), rs.getString("device_id"), rs.getString("result"),
				rs.getString("reason"), rs.getString("method"), rs.getObject("created_at", LocalDateTime.class),
				rs.getObject("reverted_at", LocalDateTime.class));
	}

	/** Registra el intento. La restricción única sobre scan_id lo vuelve idempotente (lanza DuplicateKeyException). */
	String insertCheckIn(String scanId, String organizationId, String eventId, String ticketId, String sessionId, String deviceId,
			String validatorUserId, String result, String reason, String method, LocalDateTime now) {
		String id = UUID.randomUUID().toString();
		jdbc.sql("INSERT INTO check_ins (id, scan_id, organization_id, event_id, ticket_id, session_id, device_id, validator_user_id, result, reason, method, created_at) VALUES (:id, :scan, :org, :event, :ticket, :session, :device, :user, :result, :reason, :method, :now)")
				.param("id", id).param("scan", scanId).param("org", organizationId).param("event", eventId).param("ticket", ticketId)
				.param("session", sessionId).param("device", deviceId).param("user", validatorUserId).param("result", result)
				.param("reason", reason).param("method", method).param("now", now).update();
		return id;
	}

	Optional<CheckInRow> findByScanId(String scanId) {
		return jdbc.sql("SELECT " + CHECK_IN_COLUMNS + " FROM check_ins WHERE scan_id = :scan").param("scan", scanId)
				.query(CheckInRepository::mapCheckIn).optional();
	}

	Optional<CheckInRow> findCheckIn(String id) {
		return jdbc.sql("SELECT " + CHECK_IN_COLUMNS + " FROM check_ins WHERE id = :id").param("id", id)
				.query(CheckInRepository::mapCheckIn).optional();
	}

	/** Ingresos aceptados y no revertidos de un boleto, del más antiguo al más reciente. */
	List<EntryRow> acceptedEntries(String ticketId) {
		return jdbc.sql("SELECT c.id, c.session_id, c.created_at, d.name FROM check_ins c JOIN validation_devices d ON d.id = c.device_id WHERE c.ticket_id = :ticket AND c.result = 'ACCEPTED' AND c.reverted_at IS NULL ORDER BY c.created_at, c.id")
				.param("ticket", ticketId)
				.query((rs, i) -> new EntryRow(rs.getString("id"), rs.getString("session_id"), rs.getObject("created_at", LocalDateTime.class), rs.getString("name")))
				.list();
	}

	boolean revert(String id, String eventId, String by, String reason, LocalDateTime now) {
		return jdbc.sql("UPDATE check_ins SET reverted_at = :now, reverted_by = :by, revert_reason = :reason WHERE id = :id AND event_id = :event AND result = 'ACCEPTED' AND reverted_at IS NULL")
				.param("now", now).param("by", by).param("reason", reason).param("id", id).param("event", eventId).update() == 1;
	}

	List<AuditRow> audit(String eventId, int limit) {
		return jdbc.sql("SELECT c.id, c.result, c.reason, c.method, c.created_at, c.ticket_id, t.code, tt.name AS type_name, t.attendee_name, c.session_id, d.name AS device_name, v.display_name AS validator_name, c.reverted_at, rb.display_name AS reverted_by_name, c.revert_reason FROM check_ins c JOIN validation_devices d ON d.id = c.device_id JOIN users v ON v.id = c.validator_user_id LEFT JOIN tickets t ON t.id = c.ticket_id LEFT JOIN ticket_types tt ON tt.id = t.ticket_type_id LEFT JOIN users rb ON rb.id = c.reverted_by WHERE c.event_id = :event ORDER BY c.created_at DESC, c.id LIMIT :limit")
				.param("event", eventId).param("limit", limit)
				.query((rs, i) -> new AuditRow(rs.getString("id"), rs.getString("result"), rs.getString("reason"), rs.getString("method"),
						rs.getObject("created_at", LocalDateTime.class), rs.getString("ticket_id"), rs.getString("code"), rs.getString("type_name"),
						rs.getString("attendee_name"), rs.getString("session_id"), rs.getString("device_name"), rs.getString("validator_name"),
						rs.getObject("reverted_at", LocalDateTime.class), rs.getString("reverted_by_name"), rs.getString("revert_reason")))
				.list();
	}

	/** Ingresos vigentes por sesión. */
	Map<String, Long> entriesBySession(String eventId) {
		return jdbc.sql("SELECT session_id, COUNT(*) AS n FROM check_ins WHERE event_id = :event AND result = 'ACCEPTED' AND reverted_at IS NULL GROUP BY session_id")
				.param("event", eventId).query((rs, i) -> Map.entry(String.valueOf(rs.getString("session_id")), rs.getLong("n"))).list().stream()
				.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

	long distinctTicketsEntered(String eventId) {
		return jdbc.sql("SELECT COUNT(DISTINCT ticket_id) FROM check_ins WHERE event_id = :event AND result = 'ACCEPTED' AND reverted_at IS NULL")
				.param("event", eventId).query(Long.class).single();
	}

	long rejectedCount(String eventId) {
		return jdbc.sql("SELECT COUNT(*) FROM check_ins WHERE event_id = :event AND result = 'REJECTED'").param("event", eventId)
				.query(Long.class).single();
	}

	/** Cuántos ingresos vigentes tiene cada boleto (para la búsqueda manual). */
	Map<String, Long> entryCounts(Collection<String> ticketIds) {
		if (ticketIds.isEmpty()) {
			return Map.of();
		}
		return jdbc.sql("SELECT ticket_id, COUNT(*) AS n FROM check_ins WHERE ticket_id IN (:ids) AND result = 'ACCEPTED' AND reverted_at IS NULL GROUP BY ticket_id")
				.param("ids", ticketIds).query((rs, i) -> Map.entry(rs.getString("ticket_id"), rs.getLong("n"))).list().stream()
				.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}
}
