package api.joinify.payment;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Lectura del panel de ventas del organizador (solo lectura; cruza órdenes, usuarios y pagos). */
@Repository
class SalesRepository {

	record SaleRow(String orderId, String eventTitle, String buyerEmail, String buyerName, String status, String currency,
			long subtotalMinor, long feeMinor, String feeMode, long totalMinor, LocalDateTime createdAt,
			LocalDateTime paidAt, long refundedMinor, long refundableMinor) {
	}

	private final JdbcClient jdbc;

	SalesRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	List<SaleRow> findByOrganization(String organizationId, int limit) {
		return jdbc.sql("SELECT o.id, e.title, u.email, u.display_name, o.status, o.currency, o.subtotal_minor, o.fee_minor, o.fee_mode, o.total_minor, o.created_at, o.paid_at, COALESCE(p.refunded_minor, 0) AS refunded, COALESCE(p.amount_minor - p.refunded_minor, 0) AS refundable FROM orders o JOIN events e ON e.id = o.event_id JOIN users u ON u.id = o.buyer_user_id LEFT JOIN payments p ON p.order_id = o.id AND p.status = 'SUCCEEDED' WHERE o.organization_id = :org ORDER BY o.created_at DESC, o.id LIMIT :limit")
				.param("org", organizationId).param("limit", limit)
				.query((rs, i) -> new SaleRow(rs.getString("id"), rs.getString("title"), rs.getString("email"),
						rs.getString("display_name"), rs.getString("status"), rs.getString("currency"),
						rs.getLong("subtotal_minor"), rs.getLong("fee_minor"), rs.getString("fee_mode"),
						rs.getLong("total_minor"), rs.getObject("created_at", LocalDateTime.class),
						rs.getObject("paid_at", LocalDateTime.class), rs.getLong("refunded"), rs.getLong("refundable")))
				.list();
	}
}
