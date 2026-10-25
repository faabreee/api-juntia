package api.joinify.order;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class OrderRepository {

	record OrderRow(String id, String eventId, String eventSlug, String eventTitle, String buyerId, OrderStatus status,
			String currency, long totalMinor, String requestHash, LocalDateTime createdAt, LocalDateTime expiresAt,
			LocalDateTime paidAt, String organizationId, long subtotalMinor, long feeMinor, String feeMode) {
	}

	record ItemRow(String ticketTypeId, String name, int quantity, long unitPriceMinor) {
	}

	record ItemWithId(String id, String ticketTypeId, int quantity) {
	}

	// El slug y el título del evento solo se leen para mostrar la orden.
	private static final String SELECT = "SELECT o.id, o.event_id, e.slug, e.title, o.buyer_user_id, o.status, o.currency, o.total_minor, o.request_hash, o.created_at, o.expires_at, o.paid_at, o.organization_id, o.subtotal_minor, o.fee_minor, o.fee_mode FROM orders o JOIN events e ON e.id = o.event_id";

	private final JdbcClient jdbc;

	OrderRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	private static OrderRow map(ResultSet rs, int i) throws SQLException {
		return new OrderRow(rs.getString("id"), rs.getString("event_id"), rs.getString("slug"), rs.getString("title"),
				rs.getString("buyer_user_id"), OrderStatus.valueOf(rs.getString("status")), rs.getString("currency"),
				rs.getLong("total_minor"), rs.getString("request_hash"), rs.getObject("created_at", LocalDateTime.class),
				rs.getObject("expires_at", LocalDateTime.class), rs.getObject("paid_at", LocalDateTime.class),
				rs.getString("organization_id"), rs.getLong("subtotal_minor"), rs.getLong("fee_minor"), rs.getString("fee_mode"));
	}

	/** Inserta la orden; la restricción única (comprador, clave) reclama la clave de idempotencia. */
	String insert(String eventId, String organizationId, String buyerId, OrderStatus status, String currency,
			long subtotalMinor, long feeMinor, String feeMode, long totalMinor, String key, String requestHash,
			LocalDateTime now, LocalDateTime expiresAt) {
		String id = UUID.randomUUID().toString();
		jdbc.sql("INSERT INTO orders (id, event_id, organization_id, buyer_user_id, status, currency, subtotal_minor, fee_minor, fee_mode, total_minor, idempotency_key, request_hash, created_at, expires_at, paid_at) VALUES (:id, :event, :org, :buyer, :status, :currency, :subtotal, :fee, :feeMode, :total, :key, :hash, :now, :expires, :paid)")
				.param("id", id).param("event", eventId).param("org", organizationId).param("buyer", buyerId).param("status", status.name())
				.param("subtotal", subtotalMinor).param("fee", feeMinor).param("feeMode", feeMode)
				.param("currency", currency).param("total", totalMinor).param("key", key).param("hash", requestHash)
				.param("now", now).param("expires", expiresAt).param("paid", status == OrderStatus.PAID ? now : null).update();
		return id;
	}

	void insertItem(String orderId, String ticketTypeId, String name, int quantity, long unitPriceMinor) {
		jdbc.sql("INSERT INTO order_items (id, order_id, ticket_type_id, ticket_type_name, quantity, unit_price_minor) VALUES (:id, :order, :type, :name, :qty, :price)")
				.param("id", UUID.randomUUID().toString()).param("order", orderId).param("type", ticketTypeId)
				.param("name", name).param("qty", quantity).param("price", unitPriceMinor).update();
	}

	Optional<OrderRow> findById(String id) {
		return jdbc.sql(SELECT + " WHERE o.id = :id").param("id", id).query(OrderRepository::map).optional();
	}

	Optional<OrderRow> findByBuyerAndKey(String buyerId, String key) {
		return jdbc.sql(SELECT + " WHERE o.buyer_user_id = :buyer AND o.idempotency_key = :key")
				.param("buyer", buyerId).param("key", key).query(OrderRepository::map).optional();
	}

	List<OrderRow> findByBuyer(String buyerId, int limit) {
		return jdbc.sql(SELECT + " WHERE o.buyer_user_id = :buyer ORDER BY o.created_at DESC, o.id LIMIT :limit")
				.param("buyer", buyerId).param("limit", limit).query(OrderRepository::map).list();
	}

	List<ItemRow> findItems(String orderId) {
		return jdbc.sql("SELECT ticket_type_id, ticket_type_name, quantity, unit_price_minor FROM order_items WHERE order_id = :id ORDER BY ticket_type_name, id")
				.param("id", orderId)
				.query((rs, i) -> new ItemRow(rs.getString("ticket_type_id"), rs.getString("ticket_type_name"),
						rs.getInt("quantity"), rs.getLong("unit_price_minor")))
				.list();
	}

	/** Cierra una orden pendiente (expirada o cancelada). Devuelve true solo para quien efectivamente la cerró. */
	boolean closePending(String orderId, OrderStatus to, LocalDateTime now) {
		return jdbc.sql("UPDATE orders SET status = :to, closed_at = :now WHERE id = :id AND status = 'PENDING'")
				.param("to", to.name()).param("now", now).param("id", orderId).update() == 1;
	}

	boolean markPaid(String orderId, LocalDateTime now) {
		return jdbc.sql("UPDATE orders SET status = 'PAID', paid_at = :now, expires_at = NULL WHERE id = :id AND status = 'PENDING'")
				.param("now", now).param("id", orderId).update() == 1;
	}

	List<String> findDuePendingIds(LocalDateTime now, int limit) {
		return jdbc.sql("SELECT id FROM orders WHERE status = 'PENDING' AND expires_at <= :now ORDER BY expires_at LIMIT :limit")
				.param("now", now).param("limit", limit).query(String.class).list();
	}

	/** Reabre una orden cerrada porque llegó un pago tardío y todavía hay cupo (el cupo ya se volvió a reservar). */
	boolean reopenAsPaid(String orderId, LocalDateTime now) {
		return jdbc.sql("UPDATE orders SET status = 'PAID', paid_at = :now, expires_at = NULL, closed_at = NULL WHERE id = :id AND status IN ('EXPIRED', 'CANCELLED')")
				.param("now", now).param("id", orderId).update() == 1;
	}

	boolean markRefunded(String orderId, LocalDateTime now) {
		return jdbc.sql("UPDATE orders SET status = 'REFUNDED', closed_at = :now WHERE id = :id AND status = 'PAID'")
				.param("now", now).param("id", orderId).update() == 1;
	}

	List<OrderRow> findByOrganization(String organizationId, int limit) {
		return jdbc.sql(SELECT + " WHERE o.organization_id = :org ORDER BY o.created_at DESC, o.id LIMIT :limit")
				.param("org", organizationId).param("limit", limit).query(OrderRepository::map).list();
	}

	List<ItemWithId> findItemsWithId(String orderId) {
		return jdbc.sql("SELECT id, ticket_type_id, quantity FROM order_items WHERE order_id = :id ORDER BY ticket_type_name, id")
				.param("id", orderId)
				.query((rs, i) -> new ItemWithId(rs.getString("id"), rs.getString("ticket_type_id"), rs.getInt("quantity"))).list();
	}
}
