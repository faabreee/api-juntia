package api.joinify.payment;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

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
import api.joinify.organization.OrganizationAccess;
import api.joinify.payment.PaymentGateway.GatewayException;
import api.joinify.payment.PaymentGateway.RefundRequest;
import api.joinify.payment.PaymentGateway.RefundResult;
import api.joinify.payment.PaymentRepository.PaymentRow;
import api.joinify.payment.PaymentRepository.RefundRow;
import api.joinify.payment.PaymentRepository.RefundStatus;

/**
 * Reembolsos totales y parciales (ADR 0008).
 *
 * El monto reembolsable se reserva con un UPDATE condicional atómico antes de llamar a la pasarela; si la pasarela
 * rechaza, la reserva se devuelve. El libro reparte cada reembolso entre la comisión y la parte del organizador en
 * proporción a lo que aún queda de cada una; un reembolso total devuelve exactamente lo que quede.
 */
@Service
public class RefundService {

	public record RefundView(RefundRow refund, boolean replayed) {
	}

	private static final Logger log = LoggerFactory.getLogger(RefundService.class);

	private final PaymentRepository repository;
	private final PaymentGateway gateway;
	private final OrderService orders;
	private final OrganizationAccess access;
	private final TransactionTemplate tx;
	private final Clock clock;

	RefundService(PaymentRepository repository, PaymentGateway gateway, OrderService orders, OrganizationAccess access,
			PlatformTransactionManager transactionManager, Clock clock) {
		this.repository = repository;
		this.gateway = gateway;
		this.orders = orders;
		this.access = access;
		this.tx = new TransactionTemplate(transactionManager);
		this.clock = clock;
	}

	private LocalDateTime now() {
		return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
	}

	/** Reembolso pedido por el organizador. {@code amountMinor} null = todo lo que quede. Idempotente por clave. */
	public RefundView refund(AuthUser actor, String organizationId, String orderId, Long amountMinor, String reason,
			String note, String idempotencyKey) {
		access.requireManager(organizationId, actor.getId());
		PayableOrder order = orders.payableOf(orderId);
		if (!order.organizationId().equals(organizationId)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Orden no encontrada");
		}
		PaymentRow payment = repository.findSucceededPayment(orderId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "La orden no tiene un pago confirmado"));
		return refund(payment, amountMinor, reason, note, actor.getId(), idempotencyKey);
	}

	/** Reembolso total sin intervención de nadie (pago que no se pudo honrar). */
	void refundAutomatically(PaymentRow payment, String note) {
		try {
			RefundView view = refund(payment, null, "AUTOMATIC", note, null, "auto-" + payment.id());
			if (view.refund().status() != RefundStatus.SUCCEEDED) {
				log.error("Reembolso automático del pago {} no completado: {}", payment.id(), view.refund().failureMessage());
			}
		} catch (RuntimeException e) {
			// El dinero sigue cobrado y sin orden: queda registrado para atención manual.
			log.error("Reembolso automático del pago {} falló; requiere atención manual", payment.id(), e);
		}
	}

	private RefundView refund(PaymentRow payment, Long amountMinor, String reason, String note, String requestedBy, String key) {
		var replay = repository.findRefundByKey(payment.id(), key);
		if (replay.isPresent()) {
			return replayOf(replay.get(), amountMinor);
		}
		long remaining = payment.amountMinor() - payment.refundedMinor();
		long amount = amountMinor == null ? remaining : amountMinor;
		if (amountMinor == null && remaining < 1) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "No queda nada por reembolsar");
		}
		if (amount < 1) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El monto debe ser al menos 1");
		}
		// Reserva atómica del monto: es lo que impide reembolsar de más aunque lleguen peticiones a la vez.
		if (!repository.reserveRefund(payment.id(), amount)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "El monto excede lo que aún se puede reembolsar");
		}
		String refundId;
		try {
			refundId = repository.insertRefund(payment.id(), payment.orderId(), amount, reason, note, requestedBy, key, now());
		} catch (DuplicateKeyException e) {
			repository.releaseRefund(payment.id(), amount);
			return replayOf(repository.findRefundByKey(payment.id(), key).orElseThrow(() -> e), amountMinor);
		}

		RefundResult attempt;
		try {
			attempt = gateway.refund(new RefundRequest(refundId, payment.providerPaymentId(), amount, gatewayReason(reason)));
		} catch (GatewayException e) {
			log.error("Reembolso {} sin respuesta de la pasarela", refundId, e);
			attempt = new RefundResult(false, null, "La pasarela no respondió; intenta de nuevo");
		}
		final RefundResult result = attempt;

		if (result.succeeded()) {
			tx.executeWithoutResult(status -> complete(payment, refundId, amount, result.providerRefundId()));
		} else {
			tx.executeWithoutResult(status -> {
				repository.finishRefund(refundId, RefundStatus.FAILED, null, result.failureMessage(), now());
				repository.releaseRefund(payment.id(), amount);
			});
		}
		return new RefundView(repository.findRefund(refundId).orElseThrow(), false);
	}

	private void complete(PaymentRow payment, String refundId, long amount, String providerRefundId) {
		// Se serializa el asiento en el libro por pago: dos reembolsos no leen el mismo saldo.
		repository.lockPayment(payment.id());
		long feeBalance = repository.ledgerBalance(payment.id(), "PLATFORM_FEE");
		long organizerBalance = repository.ledgerBalance(payment.id(), "ORGANIZER_PAYABLE");
		long total = feeBalance + organizerBalance;
		long feeShare = amount >= total ? feeBalance : Math.round((double) feeBalance * amount / total);
		long organizerShare = amount - feeShare;
		if (organizerShare > organizerBalance) { // el redondeo nunca debe dejar el saldo del organizador en negativo
			organizerShare = organizerBalance;
			feeShare = amount - organizerShare;
		}
		PayableOrder order = orders.payableOf(payment.orderId());
		LocalDateTime now = now();
		if (feeShare > 0) {
			repository.insertLedger(order.organizationId(), order.id(), payment.id(), refundId, "PLATFORM_FEE", -feeShare,
					payment.currency(), "refund:" + refundId + ":PLATFORM_FEE", now);
		}
		if (organizerShare > 0) {
			repository.insertLedger(order.organizationId(), order.id(), payment.id(), refundId, "ORGANIZER_PAYABLE", -organizerShare,
					payment.currency(), "refund:" + refundId + ":ORGANIZER_PAYABLE", now);
		}
		repository.finishRefund(refundId, RefundStatus.SUCCEEDED, providerRefundId, null, now);
		// Reembolso total: la orden queda REFUNDED y sus entradas vuelven al inventario.
		if (repository.findPayment(payment.id()).orElseThrow().refundedMinor() == payment.amountMinor()) {
			orders.markRefunded(payment.orderId());
		}
	}

	private RefundView replayOf(RefundRow existing, Long requestedAmount) {
		if (requestedAmount != null && requestedAmount != existing.amountMinor()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "La clave de idempotencia ya se usó con otro monto");
		}
		return new RefundView(existing, true);
	}

	/** Culqi acepta unos pocos motivos; el resto se agrupa en la solicitud del comprador. */
	private static String gatewayReason(String reason) {
		return switch (reason) {
			case "DUPLICATE" -> "duplicado";
			case "FRAUDULENT" -> "fraudulento";
			default -> "solicitud_comprador";
		};
	}
}
