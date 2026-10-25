package api.joinify.ticket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import api.joinify.order.OrderPaid;
import api.joinify.order.OrderRefunded;

/**
 * Reacciona a los eventos de dominio de las órdenes, una vez confirmada la transacción que los originó. Si algo falla
 * aquí la orden ya está pagada: no se propaga el error y {@link TicketRepairJob} completa la emisión.
 */
@Component
class TicketOrderListener {

	private static final Logger log = LoggerFactory.getLogger(TicketOrderListener.class);

	private final TicketIssuer issuer;

	TicketOrderListener(TicketIssuer issuer) {
		this.issuer = issuer;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void onOrderPaid(OrderPaid event) {
		try {
			issuer.issue(event.orderId());
		} catch (RuntimeException e) {
			log.error("No se pudieron emitir los boletos de la orden {}; se reintentará", event.orderId(), e);
		}
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void onOrderRefunded(OrderRefunded event) {
		try {
			issuer.voidTickets(event.orderId(), "REFUNDED");
		} catch (RuntimeException e) {
			log.error("No se pudieron anular los boletos de la orden {}; se reintentará", event.orderId(), e);
		}
	}
}
