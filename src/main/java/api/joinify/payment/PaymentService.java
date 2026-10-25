package api.joinify.payment;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import api.joinify.identity.AuthUser;
import api.joinify.order.OrderService;
import api.joinify.order.OrderService.PayableOrder;
import api.joinify.order.OrderStatus;
import api.joinify.payment.PaymentGateway.ChargeRequest;
import api.joinify.payment.PaymentGateway.ChargeResult;
import api.joinify.payment.PaymentGateway.GatewayException;
import api.joinify.payment.PaymentGateway.WebhookEvent;
import api.joinify.payment.PaymentRepository.PaymentRow;
import api.joinify.payment.PaymentRepository.PaymentStatus;

/**
 * Cobro de órdenes (ADR 0007 y 0008).
 *
 * Reglas: un rechazo de tarjeta no es un error del sistema; el webhook es solo un aviso y el estado real se
 * consulta a la pasarela; toda transición de estado es un UPDATE condicional, así que repetir un aviso, una
 * conciliación o una petición no duplica nada; si llega dinero que no se puede honrar, se reembolsa solo.
 */
@Service
public class PaymentService {

	public record PaymentView(PaymentRow payment, boolean replayed) {
	}

	private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
	private static final List<PaymentStatus> OPEN = List.of(PaymentStatus.CREATED, PaymentStatus.PENDING);

	private final PaymentRepository repository;
	private final PaymentGateway gateway;
	private final OrderService orders;
	private final RefundService refunds;
	private final TransactionTemplate tx;
	private final Clock clock;

	PaymentService(PaymentRepository repository, PaymentGateway gateway, OrderService orders, RefundService refunds,
			PlatformTransactionManager transactionManager, Clock clock) {
		this.repository = repository;
		this.gateway = gateway;
		this.orders = orders;
		this.refunds = refunds;
		this.tx = new TransactionTemplate(transactionManager);
		this.clock = clock;
	}

	private LocalDateTime now() {
		return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
	}

	// ---------- cobrar ----------

	/** Cobra una orden pendiente con el token que el navegador obtuvo de la pasarela. Idempotente por (orden, clave). */
	public PaymentView pay(AuthUser buyer, String orderId, String idempotencyKey, String sourceToken) {
		PayableOrder order = orders.payableOf(buyer, orderId);

		var replay = repository.findPaymentByOrderAndKey(orderId, idempotencyKey);
		if (replay.isPresent()) {
			return new PaymentView(replay.get(), true);
		}
		if (order.status() != OrderStatus.PENDING) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, switch (order.status()) {
				case PAID, REFUNDED -> "La orden ya está pagada";
				case EXPIRED -> "La reserva expiró; vuelve a elegir tus entradas";
				default -> "La orden fue cancelada";
			});
		}
		if (order.totalMinor() <= 0) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Esta orden no requiere pago");
		}
		if (order.expiresAt() != null && !order.expiresAt().isAfter(clock.instant())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "La reserva expiró; vuelve a elegir tus entradas");
		}

		// Un solo intento activo por orden: se bloquea la orden mientras se comprueba y se crea el intento.
		String paymentId;
		try {
			paymentId = tx.execute(status -> {
				repository.lockOrder(orderId);
				if (repository.hasActivePayment(orderId)) {
					throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya hay un pago en curso o completado para esta orden");
				}
				return repository.insertPayment(orderId, gateway.name(), order.totalMinor(), order.currency(), idempotencyKey, now());
			});
		} catch (DuplicateKeyException e) {
			return new PaymentView(repository.findPaymentByOrderAndKey(orderId, idempotencyKey).orElseThrow(() -> e), true);
		}

		// El cobro es una llamada de red: se hace fuera de cualquier transacción.
		ChargeResult result;
		try {
			result = gateway.charge(new ChargeRequest(paymentId, orderId, order.totalMinor(), order.currency(),
					buyer.getEmail(), sourceToken, "Orden " + order.eventTitle()));
		} catch (GatewayException e) {
			// No sabemos si cobró: queda pendiente y la conciliación lo resuelve.
			log.warn("Cobro sin respuesta de la pasarela (pago {})", paymentId, e);
			repository.transition(paymentId, List.of(PaymentStatus.CREATED), PaymentStatus.PENDING, null, null, null, now());
			return new PaymentView(repository.findPayment(paymentId).orElseThrow(), false);
		}
		sync(paymentId, result);
		return new PaymentView(repository.findPayment(paymentId).orElseThrow(), false);
	}

	public PaymentRow get(AuthUser buyer, String orderId, String paymentId) {
		orders.payableOf(buyer, orderId); // comprueba que la orden es suya
		return repository.findPayment(paymentId).filter(p -> p.orderId().equals(orderId))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pago no encontrado"));
	}

	// ---------- aplicar el resultado de la pasarela (cobro, webhook y conciliación convergen aquí) ----------

	/** Aplica el estado de la pasarela al pago. Seguro de repetir: cada transición es condicional. */
	String sync(String paymentId, ChargeResult result) {
		switch (result.status()) {
			case FAILED -> {
				boolean changed = repository.transition(paymentId, OPEN, PaymentStatus.FAILED, result.providerPaymentId(),
						result.failureCode(), result.failureMessage(), now());
				return changed ? "payment-failed" : "no-op";
			}
			case PENDING -> {
				boolean changed = repository.transition(paymentId, List.of(PaymentStatus.CREATED), PaymentStatus.PENDING,
						result.providerPaymentId(), null, null, now());
				return changed ? "payment-pending" : "no-op";
			}
			default -> {
				return onSucceeded(paymentId, result.providerPaymentId());
			}
		}
	}

	private String onSucceeded(String paymentId, String providerPaymentId) {
		boolean needsRefund = Boolean.TRUE.equals(tx.execute(status -> {
			if (!repository.transition(paymentId, OPEN, PaymentStatus.SUCCEEDED, providerPaymentId, null, null, now())) {
				return false; // ya estaba resuelto: repetir no hace nada
			}
			PaymentRow payment = repository.findPayment(paymentId).orElseThrow();
			PayableOrder order = orders.payableOf(payment.orderId());
			writeSaleEntries(payment, order);
			if (orders.confirmPayment(order.id())) {
				return false;
			}
			// La orden ya no estaba pendiente: llegó tarde (expirada o cancelada) o es un pago duplicado.
			OrderStatus current = orders.payableOf(order.id()).status();
			if ((current == OrderStatus.EXPIRED || current == OrderStatus.CANCELLED) && orders.reviveWithPayment(order.id())) {
				return false; // todavía había cupo: la orden queda pagada
			}
			return true;
		}));
		if (needsRefund) {
			// El dinero llegó pero no se puede honrar: se devuelve completo y de inmediato (fuera de la transacción).
			PaymentRow payment = repository.findPayment(paymentId).orElseThrow();
			log.warn("Pago {} recibido sin poder honrar la orden {}: reembolso automático", paymentId, payment.orderId());
			refunds.refundAutomatically(payment, "Pago recibido tras expirar la reserva y sin cupo, o duplicado");
			return "succeeded-auto-refunded";
		}
		return "succeeded";
	}

	/**
	 * Asienta el cobro en el libro: lo del organizador (neto) y la comisión de la plataforma. Su suma es siempre el
	 * monto cobrado. Si la comisión la paga el comprador el neto es el subtotal; si la absorbe el organizador, el
	 * subtotal menos la comisión; en ambos casos neto = total − comisión.
	 */
	private void writeSaleEntries(PaymentRow payment, PayableOrder order) {
		long platformFee = order.feeMinor();
		long organizerNet = order.totalMinor() - platformFee;
		if (payment.amountMinor() != order.totalMinor() || organizerNet < 0) {
			throw new IllegalStateException("El monto cobrado no coincide con la orden " + order.id());
		}
		LocalDateTime now = now();
		repository.insertLedger(order.organizationId(), order.id(), payment.id(), null, "ORGANIZER_PAYABLE", organizerNet,
				payment.currency(), "sale:" + payment.id() + ":ORGANIZER_PAYABLE", now);
		repository.insertLedger(order.organizationId(), order.id(), payment.id(), null, "PLATFORM_FEE", platformFee,
				payment.currency(), "sale:" + payment.id() + ":PLATFORM_FEE", now);
	}

	// ---------- webhooks ----------

	public enum WebhookOutcome {
		PROCESSED, DUPLICATE
	}

	/**
	 * Procesa un aviso de la pasarela. Idempotente: el mismo evento dos veces se procesa una sola vez. Si el
	 * procesamiento falla, el aviso queda sin procesar y la pasarela (o la conciliación) lo reintenta.
	 */
	public WebhookOutcome handleWebhook(String provider, Map<String, String> headers, String rawBody, String pathSecret) {
		if (!provider.equals(gateway.name())) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Proveedor desconocido");
		}
		WebhookEvent event;
		try {
			event = gateway.parseWebhook(headers, rawBody, pathSecret);
		} catch (PaymentGateway.InvalidWebhookException e) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Webhook no auténtico");
		}
		var recorded = repository.recordWebhook(provider, event.eventId(), event.type(), rawBody, now());
		if (recorded.isEmpty()) {
			return WebhookOutcome.DUPLICATE;
		}
		String outcome = process(provider, event);
		repository.finishWebhook(recorded.get(), outcome, now());
		return WebhookOutcome.PROCESSED;
	}

	private String process(String provider, WebhookEvent event) {
		if (!event.type().startsWith("charge.") || event.providerPaymentId() == null) {
			return "ignored:" + event.type(); // los reembolsos se resuelven al pedirlos
		}
		return repository.findPaymentByProviderId(provider, event.providerPaymentId()).map(payment -> {
			// El aviso no se cree: el estado real se consulta a la pasarela.
			return sync(payment.id(), gateway.fetchCharge(event.providerPaymentId()));
		}).orElse("unknown-payment");
	}

	// ---------- conciliación ----------

	private static final long LOST_AFTER_MINUTES = 10;
	private static final long GRACE_MINUTES = 2;

	/** Resuelve pagos que quedaron abiertos (aviso perdido, pasarela caída). Devuelve cuántos cambiaron. */
	public int reconcilePending() {
		LocalDateTime cutoff = now().minusMinutes(GRACE_MINUTES);
		int changed = 0;
		for (PaymentRow payment : repository.findStale(List.of("CREATED", "PENDING"), cutoff, 100)) {
			try {
				if (payment.providerPaymentId() == null) {
					// Nunca supimos el id del cobro: pasado el plazo se da por no realizado.
					if (payment.createdAt().isBefore(now().minusMinutes(LOST_AFTER_MINUTES))
							&& repository.transition(payment.id(), OPEN, PaymentStatus.FAILED, null, "no_response",
									"La pasarela no confirmó el cobro", now())) {
						changed++;
					}
					continue;
				}
				String outcome = sync(payment.id(), gateway.fetchCharge(payment.providerPaymentId()));
				if (!outcome.equals("no-op") && !outcome.equals("payment-pending")) {
					changed++;
				}
			} catch (GatewayException e) {
				log.warn("Conciliación: no se pudo consultar el pago {}", payment.id(), e);
			}
		}
		return changed;
	}

	/**
	 * Verifica el libro: para cada pago cobrado, lo asentado debe ser igual a lo cobrado menos lo devuelto, y lo
	 * devuelto debe coincidir con los reembolsos exitosos. Devuelve las inconsistencias (vacío = todo cuadra).
	 */
	public List<String> checkLedger() {
		List<String> problems = new java.util.ArrayList<>();
		for (PaymentRow payment : repository.findAllSucceeded()) {
			long balance = repository.ledgerBalance(payment.id(), "ORGANIZER_PAYABLE") + repository.ledgerBalance(payment.id(), "PLATFORM_FEE");
			if (balance != payment.amountMinor() - payment.refundedMinor()) {
				problems.add("Pago " + payment.id() + ": el libro suma " + balance + " pero debería sumar "
						+ (payment.amountMinor() - payment.refundedMinor()));
			}
			long refunded = repository.sumSucceededRefunds(payment.id());
			if (refunded != payment.refundedMinor()) {
				problems.add("Pago " + payment.id() + ": reembolsos exitosos " + refunded + " ≠ devuelto " + payment.refundedMinor());
			}
		}
		return problems;
	}

	public PaymentGateway.PublicConfig publicConfig() {
		return gateway.publicConfig();
	}

	Instant instantNow() {
		return clock.instant();
	}
}
