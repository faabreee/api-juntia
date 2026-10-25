package api.joinify.event;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class TicketTypeRepository {

	record Row(String id, String eventId, String name, String description, String template, long priceMinor,
			String currency, Integer quota, int maxPerOrder, LocalDateTime salesStart, LocalDateTime salesEnd,
			AccessRule.Scope scope, Integer maxTotalUses, Integer maxUsesPerSession, int earlyEntryMinutes,
			boolean active, int taken) {
	}

	/** Datos editables de un tipo de entrada (sin id ni moneda). */
	record Data(String name, String description, String template, long priceMinor, Integer quota, int maxPerOrder,
			LocalDateTime salesStart, LocalDateTime salesEnd, AccessRule rule, boolean active) {
	}

	private static final String COLUMNS = "id, event_id, name, description, template, price_minor, currency, quota, max_per_order, sales_start, sales_end, access_scope, max_total_uses, max_uses_per_session, early_entry_minutes, active, taken";

	private final JdbcClient jdbc;

	TicketTypeRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	private static LocalDateTime now() {
		return LocalDateTime.now(ZoneOffset.UTC);
	}

	private static Row map(ResultSet rs, int i) throws SQLException {
		return new Row(rs.getString("id"), rs.getString("event_id"), rs.getString("name"), rs.getString("description"),
				rs.getString("template"), rs.getLong("price_minor"), rs.getString("currency"),
				rs.getObject("quota", Integer.class), rs.getInt("max_per_order"),
				rs.getObject("sales_start", LocalDateTime.class), rs.getObject("sales_end", LocalDateTime.class),
				AccessRule.Scope.valueOf(rs.getString("access_scope")), rs.getObject("max_total_uses", Integer.class),
				rs.getObject("max_uses_per_session", Integer.class), rs.getInt("early_entry_minutes"),
				rs.getBoolean("active"), rs.getInt("taken"));
	}

	String insert(String eventId, String currency, Data d) {
		String id = UUID.randomUUID().toString();
		LocalDateTime now = now();
		jdbc.sql("INSERT INTO ticket_types (id, event_id, name, description, template, price_minor, currency, quota, max_per_order, sales_start, sales_end, access_scope, max_total_uses, max_uses_per_session, early_entry_minutes, active, created_at, updated_at) VALUES (:id, :event, :name, :description, :template, :price, :currency, :quota, :perOrder, :salesStart, :salesEnd, :scope, :total, :perSession, :early, :active, :now, :now)")
				.param("id", id).param("event", eventId).param("currency", currency).param("now", now)
				.params(common(d)).update();
		replaceSessions(id, d.rule());
		return id;
	}

	void update(String id, Data d) {
		jdbc.sql("UPDATE ticket_types SET name = :name, description = :description, template = :template, price_minor = :price, quota = :quota, max_per_order = :perOrder, sales_start = :salesStart, sales_end = :salesEnd, access_scope = :scope, max_total_uses = :total, max_uses_per_session = :perSession, early_entry_minutes = :early, active = :active, updated_at = :now WHERE id = :id")
				.param("id", id).param("now", now()).params(common(d)).update();
		replaceSessions(id, d.rule());
	}

	private static java.util.Map<String, Object> common(Data d) {
		java.util.Map<String, Object> m = new java.util.HashMap<>();
		m.put("name", d.name());
		m.put("description", d.description());
		m.put("template", d.template());
		m.put("price", d.priceMinor());
		m.put("quota", d.quota());
		m.put("perOrder", d.maxPerOrder());
		m.put("salesStart", d.salesStart());
		m.put("salesEnd", d.salesEnd());
		m.put("scope", d.rule().scope().name());
		m.put("total", d.rule().maxTotalUses());
		m.put("perSession", d.rule().maxUsesPerSession());
		m.put("early", d.rule().earlyEntryMinutes());
		m.put("active", d.active());
		return m;
	}

	private void replaceSessions(String ticketTypeId, AccessRule rule) {
		jdbc.sql("DELETE FROM ticket_type_sessions WHERE ticket_type_id = :id").param("id", ticketTypeId).update();
		if (rule.scope() == AccessRule.Scope.SELECTED_SESSIONS) {
			for (String sessionId : rule.sessionIds()) {
				jdbc.sql("INSERT INTO ticket_type_sessions (ticket_type_id, session_id) VALUES (:type, :session)")
						.param("type", ticketTypeId).param("session", sessionId).update();
			}
		}
	}

	List<Row> findByEvent(String eventId, boolean onlyActive) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM ticket_types WHERE event_id = :event" + (onlyActive ? " AND active = TRUE" : "") + " ORDER BY created_at, id")
				.param("event", eventId).query(TicketTypeRepository::map).list();
	}

	Optional<Row> find(String eventId, String id) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM ticket_types WHERE id = :id AND event_id = :event")
				.param("id", id).param("event", eventId).query(TicketTypeRepository::map).optional();
	}

	Set<String> sessionIds(String ticketTypeId) {
		return new LinkedHashSet<>(jdbc.sql("SELECT session_id FROM ticket_type_sessions WHERE ticket_type_id = :id ORDER BY session_id")
				.param("id", ticketTypeId).query(String.class).list());
	}

	void delete(String id) {
		jdbc.sql("DELETE FROM ticket_types WHERE id = :id").param("id", id).update();
	}

	boolean sessionInUse(String sessionId) {
		return jdbc.sql("SELECT COUNT(*) FROM ticket_type_sessions WHERE session_id = :id").param("id", sessionId)
				.query(Long.class).single() > 0;
	}

	/**
	 * Reserva cupo con un UPDATE condicional atómico: la fila queda bloqueada hasta el commit, así que dos
	 * compradores nunca ven el mismo cupo libre. Devuelve false si no alcanza.
	 */
	boolean tryReserve(String ticketTypeId, int quantity) {
		return jdbc.sql("UPDATE ticket_types SET taken = taken + :qty WHERE id = :id AND (quota IS NULL OR taken + :qty <= quota)")
				.param("qty", quantity).param("id", ticketTypeId).update() == 1;
	}

	void release(String ticketTypeId, int quantity) {
		jdbc.sql("UPDATE ticket_types SET taken = taken - :qty WHERE id = :id AND taken >= :qty")
				.param("qty", quantity).param("id", ticketTypeId).update();
	}

	long totalTaken(String eventId) {
		return jdbc.sql("SELECT COALESCE(SUM(taken), 0) FROM ticket_types WHERE event_id = :event").param("event", eventId)
				.query(Long.class).single();
	}

	Optional<Row> findById(String id) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM ticket_types WHERE id = :id").param("id", id)
				.query(TicketTypeRepository::map).optional();
	}
}
