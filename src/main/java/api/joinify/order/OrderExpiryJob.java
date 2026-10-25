package api.joinify.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Expira las órdenes pendientes cuyo tiempo de reserva venció y libera su cupo. */
@Component
class OrderExpiryJob {

	private static final Logger log = LoggerFactory.getLogger(OrderExpiryJob.class);

	private final OrderService orders;

	OrderExpiryJob(OrderService orders) {
		this.orders = orders;
	}

	@Scheduled(fixedDelayString = "${app.orders.expiry-interval-ms}")
	void expireDueOrders() {
		try {
			int closed = orders.expireDue();
			if (closed > 0) {
				log.info("{} orden(es) expirada(s)", closed);
			}
		} catch (RuntimeException e) {
			log.error("Falló el barrido de órdenes vencidas", e);
		}
	}
}
