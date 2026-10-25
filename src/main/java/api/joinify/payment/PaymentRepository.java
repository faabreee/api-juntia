package api.joinify.payment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class PaymentRepository {

	enum PaymentStatus {
		CREATED, PENDING, SUCCEEDED, FAILED
	}

	enum RefundStatus {
		PENDING, SUCCEEDED, FAILED
	}

	record PaymentRow(String id, String orderId, String provider, String providerPaymentId, PaymentStatus status,
			long amountMinor, String currency, long refundedMinor, String failureCode, String failureMessage,
			LocalDateTime createdAt) {
		long refundableMinor() {
			return status == PaymentStatus.SUCCEEDED ? amountMinor - refundedMinor : 0;
		}
	}

	record RefundRow(String id, String paymentId, String orderId, long amountMinor, RefundStatus status, String reason,
			String note, String providerRefundId, String failureMessage) {
	}

	record WebhookRow(String id, boolean processed) {
	}

	private static final String PAYMENT_COLUMNS = "id, order_id, provider, provider_payment_id, status, amount_minor, currency, refunded_minor, failure_code, failure_message, created_at";
	private static final String REFUND_COLUMNS = "id, payment_id, order_id, amount_minor, status, reason, note, provider_refund_id, failure_message";

	private final JdbcClient jdbc;

	PaymentRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	// ---------- pagos ----------

	private static PaymentRow mapPayment(ResultSet rs, int i) throws SQLException {
		return new PaymentRow(rs.getString("id"), rs.getString("order_id"), rs.getString("provider"),
				rs.getString("provider_payment_id"), PaymentStatus.valueOf(rs.getString("status")), rs.getLong("amount_minor"),
				rs.getString("currency"), rs.getLong("refunded_minor"), rs.getString("failure_code"),
				rs.getString("failure_message"), rs.getObject("created_at", LocalDateTime.class));
	}

	/** Bloquea la fila de la orden hasta el fin de la transacción: serializa los intentos de pago de una misma orden. */
	void lockOrder(String orderId) {
		jdbc.sql("SELECT id FROM orders WHERE id = :id FOR UPDATE").param("id", orderId).query(String.class).optional();
	}

	boolean hasActivePayment(String orderId) {
		return jdbc.sql("SELECT COUNT(*) FROM payments WHERE order_id = :id AND status IN ('CREATED', 'PENDING', 'SUCCEEDED')")
				.param("id", orderId).query(Long.class).single() > 0;
	}

	String insertPayment(String orderId, String provider, long amountMinor, String currency, String key, LocalDateTime now) {
		String id = UUID.randomUUID().toString();
		jdbc.sql("INSERT INTO payments (id, order_id, provider, status, amount_minor, currency, idempotency_key, created_at, updated_at) VALUES (:id, :order, :provider, 'CREATED', :amount, :currency, :key, :now, :now)")
				.param("id", id).param("order", orderId).param("provider", provider).param("amount", amountMinor)
				.param("currency", currency).param("key", key).param("now", now).update();
		return id;
	}

	Optional<PaymentRow> findPayment(String id) {
		return jdbc.sql("SELECT " + PAYMENT_COLUMNS + " FROM payments WHERE id = :id").param("id", id)
				.query(PaymentRepository::mapPayment).optional();
	}

	Optional<PaymentRow> findPaymentByOrderAndKey(String orderId, String key) {
		return jdbc.sql("SELECT " + PAYMENT_COLUMNS + " FROM payments WHERE order_id = :order AND idempotency_key = :key")
				.param("order", orderId).param("key", key).query(PaymentRepository::mapPayment).optional();
	}

	Optional<PaymentRow> findPaymentByProviderId(String provider, String providerPaymentId) {
		return jdbc.sql("SELECT " + PAYMENT_COLUMNS + " FROM payments WHERE provider = :provider AND provider_payment_id = :pid")
				.param("provider", provider).param("pid", providerPaymentId).query(PaymentRepository::mapPayment).optional();
	}

	Optional<PaymentRow> findSucceededPayment(String orderId) {
		return jdbc.sql("SELECT " + PAYMENT_COLUMNS + " FROM payments WHERE order_id = :order AND status = 'SUCCEEDED' ORDER BY created_at DESC LIMIT 1")
				.param("order", orderId).query(PaymentRepository::mapPayment).optional();
	}

	List<PaymentRow> findStale(Collection<String> statuses, LocalDateTime olderThan, int limit) {
		return jdbc.sql("SELECT " + PAYMENT_COLUMNS + " FROM payments WHERE status IN (:statuses) AND created_at < :before ORDER BY created_at LIMIT :limit")
				.param("statuses", statuses).param("before", olderThan).param("limit", limit)
				.query(PaymentRepository::mapPayment).list();
	}

	List<PaymentRow> findAllSucceeded() {
		return jdbc.sql("SELECT " + PAYMENT_COLUMNS + " FROM payments WHERE status = 'SUCCEEDED'")
				.query(PaymentRepository::mapPayment).list();
	}

	/** Cambia el estado solo si el pago sigue en uno de los estados de origen (transición atómica e idempotente). */
	boolean transition(String id, Collection<PaymentStatus> from, PaymentStatus to, String providerPaymentId,
			String failureCode, String failureMessage, LocalDateTime now) {
		return jdbc.sql("UPDATE payments SET status = :to, provider_payment_id = COALESCE(provider_payment_id, :pid), failure_code = :code, failure_message = :message, updated_at = :now WHERE id = :id AND status IN (:from)")
				.param("to", to.name()).param("pid", providerPaymentId).param("code", failureCode)
				.param("message", failureMessage).param("now", now).param("id", id)
				.param("from", from.stream().map(Enum::name).toList()).update() == 1;
	}

	// ---------- reembolsos ----------

	private static RefundRow mapRefund(ResultSet rs, int i) throws SQLException {
		return new RefundRow(rs.getString("id"), rs.getString("payment_id"), rs.getString("order_id"), rs.getLong("amount_minor"),
				RefundStatus.valueOf(rs.getString("status")), rs.getString("reason"), rs.getString("note"),
				rs.getString("provider_refund_id"), rs.getString("failure_message"));
	}

	/**
	 * Reserva monto reembolsable con un UPDATE condicional atómico (como el cupo): dos reembolsos simultáneos nunca
	 * pueden sumar más que lo cobrado.
	 */
	boolean reserveRefund(String paymentId, long amount) {
		return jdbc.sql("UPDATE payments SET refunded_minor = refunded_minor + :a WHERE id = :id AND status = 'SUCCEEDED' AND refunded_minor + :a <= amount_minor")
				.param("a", amount).param("id", paymentId).update() == 1;
	}

	void releaseRefund(String paymentId, long amount) {
		jdbc.sql("UPDATE payments SET refunded_minor = refunded_minor - :a WHERE id = :id AND refunded_minor >= :a")
				.param("a", amount).param("id", paymentId).update();
	}

	String insertRefund(String paymentId, String orderId, long amount, String reason, String note, String requestedBy,
			String key, LocalDateTime now) {
		String id = UUID.randomUUID().toString();
		jdbc.sql("INSERT INTO refunds (id, payment_id, order_id, amount_minor, status, reason, note, requested_by, idempotency_key, created_at, updated_at) VALUES (:id, :payment, :order, :amount, 'PENDING', :reason, :note, :by, :key, :now, :now)")
				.param("id", id).param("payment", paymentId).param("order", orderId).param("amount", amount)
				.param("reason", reason).param("note", note).param("by", requestedBy).param("key", key).param("now", now).update();
		return id;
	}

	Optional<RefundRow> findRefund(String id) {
		return jdbc.sql("SELECT " + REFUND_COLUMNS + " FROM refunds WHERE id = :id").param("id", id)
				.query(PaymentRepository::mapRefund).optional();
	}

	Optional<RefundRow> findRefundByKey(String paymentId, String key) {
		return jdbc.sql("SELECT " + REFUND_COLUMNS + " FROM refunds WHERE payment_id = :payment AND idempotency_key = :key")
				.param("payment", paymentId).param("key", key).query(PaymentRepository::mapRefund).optional();
	}

	void finishRefund(String id, RefundStatus status, String providerRefundId, String failureMessage, LocalDateTime now) {
		jdbc.sql("UPDATE refunds SET status = :status, provider_refund_id = :pid, failure_message = :message, updated_at = :now WHERE id = :id AND status = 'PENDING'")
				.param("status", status.name()).param("pid", providerRefundId).param("message", failureMessage)
				.param("now", now).param("id", id).update();
	}

	long sumSucceededRefunds(String paymentId) {
		return jdbc.sql("SELECT COALESCE(SUM(amount_minor), 0) FROM refunds WHERE payment_id = :id AND status = 'SUCCEEDED'")
				.param("id", paymentId).query(Long.class).single();
	}

	// ---------- libro de movimientos ----------

	void lockPayment(String paymentId) {
		jdbc.sql("SELECT id FROM payments WHERE id = :id FOR UPDATE").param("id", paymentId).query(String.class).optional();
	}

	void insertLedger(String organizationId, String orderId, String paymentId, String refundId, String kind, long amount,
			String currency, String sourceKey, LocalDateTime now) {
		jdbc.sql("INSERT INTO ledger_entries (id, organization_id, order_id, payment_id, refund_id, kind, amount_minor, currency, source_key, created_at) VALUES (:id, :org, :order, :payment, :refund, :kind, :amount, :currency, :key, :now)")
				.param("id", UUID.randomUUID().toString()).param("org", organizationId).param("order", orderId)
				.param("payment", paymentId).param("refund", refundId).param("kind", kind).param("amount", amount)
				.param("currency", currency).param("key", sourceKey).param("now", now).update();
	}

	long ledgerBalance(String paymentId, String kind) {
		return jdbc.sql("SELECT COALESCE(SUM(amount_minor), 0) FROM ledger_entries WHERE payment_id = :id AND kind = :kind")
				.param("id", paymentId).param("kind", kind).query(Long.class).single();
	}

	// ---------- webhooks ----------

	/** Registra el aviso. Devuelve vacío si ya existía y fue procesado; devuelve el id si es nuevo o quedó sin procesar. */
	Optional<String> recordWebhook(String provider, String eventId, String type, String payload, LocalDateTime now) {
		try {
			String id = UUID.randomUUID().toString();
			jdbc.sql("INSERT INTO webhook_events (id, provider, event_id, event_type, payload, received_at) VALUES (:id, :provider, :event, :type, :payload, :now)")
					.param("id", id).param("provider", provider).param("event", eventId).param("type", type)
					.param("payload", payload).param("now", now).update();
			return Optional.of(id);
		} catch (org.springframework.dao.DuplicateKeyException e) {
			return jdbc.sql("SELECT id, processed_at FROM webhook_events WHERE provider = :provider AND event_id = :event")
					.param("provider", provider).param("event", eventId)
					.query((rs, i) -> new WebhookRow(rs.getString("id"), rs.getObject("processed_at") != null)).optional()
					.filter(row -> !row.processed()).map(WebhookRow::id);
		}
	}

	void finishWebhook(String id, String outcome, LocalDateTime now) {
		jdbc.sql("UPDATE webhook_events SET outcome = :outcome, processed_at = :now WHERE id = :id")
				.param("outcome", outcome).param("now", now).param("id", id).update();
	}
}
