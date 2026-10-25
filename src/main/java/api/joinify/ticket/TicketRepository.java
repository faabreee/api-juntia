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
