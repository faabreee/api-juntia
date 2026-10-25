package api.joinify.ticket;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class TicketRepository {

	enum Status {
		VALID, VOID
	}

	/** Boleto con los datos de su entrada y evento para mostrarlo (lectura cruzada solo para presentación). */
	record TicketRow(String id, String orderId, String code, Status status, String attendeeName, boolean nominative,
			String ticketTypeId, String ticketTypeName, String eventId, String eventSlug, String eventTitle,
			String timezone, LocalDateTime issuedAt, LocalDateTime firstSessionAt, LocalDateTime lastSessionAt) {
	}

	private static final String SELECT = "SELECT t.id, t.order_id, t.code, t.status, t.attendee_name, tt.nominative, tt.id AS type_id, tt.name AS type_name, e.id AS event_id, e.slug, e.title, e.timezone, t.issued_at, (SELECT MIN(s.starts_at) FROM event_sessions s WHERE s.event_id = e.id) AS first_at, (SELECT MAX(s.ends_at) FROM event_sessions s WHERE s.event_id = e.id) AS last_at FROM tickets t JOIN ticket_types tt ON tt.id = t.ticket_type_id JOIN events e ON e.id = t.event_id";

	private final JdbcClient jdbc;

	TicketRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	private static TicketRow map(ResultSet rs, int i) throws SQLException {
		return new TicketRow(rs.getString("id"), rs.getString("order_id"), rs.getString("code"), Status.valueOf(rs.getString("status")),
				rs.getString("attendee_name"), rs.getBoolean("nominative"), rs.getString("type_id"), rs.getString("type_name"),
				rs.getString("event_id"), rs.getString("slug"), rs.getString("title"), rs.getString("timezone"),
				rs.getObject("issued_at", LocalDateTime.class), rs.getObject("first_at", LocalDateTime.class),
				rs.getObject("last_at", LocalDateTime.class));
	}

	void insert(String id, String orderId, String orderItemId, int seq, String eventId, String ticketTypeId, String ownerId,
			String code, LocalDateTime now) {
		jdbc.sql("INSERT INTO tickets (id, order_id, order_item_id, seq, event_id, ticket_type_id, owner_user_id, code, status, issued_at) VALUES (:id, :order, :item, :seq, :event, :type, :owner, :code, 'VALID', :now)")
				.param("id", id).param("order", orderId).param("item", orderItemId).param("seq", seq).param("event", eventId)
				.param("type", ticketTypeId).param("owner", ownerId).param("code", code).param("now", now).update();
	}

	/** Bloquea la orden hasta el fin de la transacción: serializa a los emisores de una misma orden. */
	void lockOrder(String orderId) {
		jdbc.sql("SELECT id FROM orders WHERE id = :id FOR UPDATE").param("id", orderId).query(String.class).optional();
	}

	Optional<TicketRow> findById(String ticketId) {
		return jdbc.sql(SELECT + " WHERE t.id = :id").param("id", ticketId).query(TicketRepository::map).optional();
	}

	Optional<TicketRow> findByEventAndCode(String eventId, String code) {
		return jdbc.sql(SELECT + " WHERE t.event_id = :event AND t.code = :code").param("event", eventId).param("code", code)
				.query(TicketRepository::map).optional();
	}

	void lockTicket(String ticketId) {
		jdbc.sql("SELECT id FROM tickets WHERE id = :id FOR UPDATE").param("id", ticketId).query(String.class).optional();
	}

	void markFirstCheckIn(String ticketId, LocalDateTime when) {
		jdbc.sql("UPDATE tickets SET first_checkin_at = COALESCE(first_checkin_at, :when) WHERE id = :id").param("when", when)
				.param("id", ticketId).update();
	}

	boolean hasCheckedIn(String ticketId) {
		return jdbc.sql("SELECT COUNT(*) FROM tickets WHERE id = :id AND first_checkin_at IS NOT NULL").param("id", ticketId)
				.query(Long.class).single() > 0;
	}

	long countValidByEvent(String eventId) {
		return jdbc.sql("SELECT COUNT(*) FROM tickets WHERE event_id = :event AND status = 'VALID'").param("event", eventId)
				.query(Long.class).single();
	}

	List<TicketDirectory.AttendeeMatch> search(String eventId, String query, String code, int limit) {
		String like = "%" + query.toLowerCase(java.util.Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
		return jdbc.sql("SELECT t.id, t.code, t.status, tt.name AS type_name, t.attendee_name, u.display_name, u.email FROM tickets t JOIN ticket_types tt ON tt.id = t.ticket_type_id JOIN users u ON u.id = t.owner_user_id WHERE t.event_id = :event AND (t.code = :code OR LOWER(t.attendee_name) LIKE :like ESCAPE '\\\\' OR LOWER(u.email) LIKE :like ESCAPE '\\\\' OR LOWER(u.display_name) LIKE :like ESCAPE '\\\\') ORDER BY t.attendee_name, u.display_name, t.code LIMIT :limit")
				.param("event", eventId).param("code", code == null ? "" : code).param("like", like).param("limit", limit)
				.query((rs, i) -> new TicketDirectory.AttendeeMatch(rs.getString("id"), ShortCode.format(rs.getString("code")),
						Status.valueOf(rs.getString("status")) == Status.VALID, rs.getString("type_name"), rs.getString("attendee_name"),
						rs.getString("display_name"), mask(rs.getString("email"))))
				.list();
	}

	/** a***@dominio.com: el personal de puerta reconoce al comprador sin ver su correo completo. */
	private static String mask(String email) {
		int at = email.indexOf('@');
		return at <= 1 ? email : email.charAt(0) + "***" + email.substring(at);
	}

	boolean exists(String orderItemId, int seq) {
		return jdbc.sql("SELECT COUNT(*) FROM tickets WHERE order_item_id = :item AND seq = :seq").param("item", orderItemId)
				.param("seq", seq).query(Long.class).single() > 0;
	}

	List<TicketRow> findByOwner(String ownerId) {
		return jdbc.sql(SELECT + " WHERE t.owner_user_id = :owner ORDER BY first_at, t.issued_at, t.order_id, t.seq")
				.param("owner", ownerId).query(TicketRepository::map).list();
	}

	List<TicketRow> findByOrder(String orderId, String ownerId) {
		return jdbc.sql(SELECT + " WHERE t.order_id = :order AND t.owner_user_id = :owner ORDER BY t.seq, t.issued_at")
				.param("order", orderId).param("owner", ownerId).query(TicketRepository::map).list();
	}

	/** Todos los boletos de una orden, sin comprobar dueño (correo de entrega). */
	List<TicketRow> findAllByOrder(String orderId) {
		return jdbc.sql(SELECT + " WHERE t.order_id = :order ORDER BY t.seq, t.issued_at").param("order", orderId)
				.query(TicketRepository::map).list();
	}

	Optional<TicketRow> findOwned(String ticketId, String ownerId) {
		return jdbc.sql(SELECT + " WHERE t.id = :id AND t.owner_user_id = :owner").param("id", ticketId).param("owner", ownerId)
				.query(TicketRepository::map).optional();
	}

	int voidByOrder(String orderId, String reason, LocalDateTime now) {
		return jdbc.sql("UPDATE tickets SET status = 'VOID', voided_at = :now, void_reason = :reason WHERE order_id = :order AND status = 'VALID'")
				.param("now", now).param("reason", reason).param("order", orderId).update();
	}

	boolean updateAttendee(String ticketId, String name) {
		return jdbc.sql("UPDATE tickets SET attendee_name = :name WHERE id = :id AND status = 'VALID'").param("name", name)
				.param("id", ticketId).update() == 1;
	}

	/** Órdenes pagadas a las que les faltan boletos (emisión interrumpida). Lectura de órdenes solo para reparar. */
	List<String> findPaidOrdersMissingTickets(LocalDateTime paidBefore, int limit) {
		return jdbc.sql("SELECT o.id FROM orders o WHERE o.status = 'PAID' AND o.paid_at < :before AND (SELECT COALESCE(SUM(oi.quantity), 0) FROM order_items oi WHERE oi.order_id = o.id) > (SELECT COUNT(*) FROM tickets t WHERE t.order_id = o.id) ORDER BY o.paid_at LIMIT :limit")
				.param("before", paidBefore).param("limit", limit).query(String.class).list();
	}

	/** Órdenes reembolsadas que todavía tienen boletos vigentes. */
	List<String> findRefundedOrdersWithValidTickets(int limit) {
		return jdbc.sql("SELECT DISTINCT t.order_id FROM tickets t JOIN orders o ON o.id = t.order_id WHERE o.status = 'REFUNDED' AND t.status = 'VALID' LIMIT :limit")
				.param("limit", limit).query(String.class).list();
	}
}
