package api.joinify.order;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import api.joinify.identity.UserDirectory;
import api.joinify.notification.EmailFormat;
import api.joinify.notification.EmailLayout;
import api.joinify.notification.NotificationRenderer;
import api.joinify.notification.NotificationService;
import api.joinify.order.OrderService.OrderSummary;

/** Correo de confirmación de compra: se encola al quedar pagada la orden y se arma al enviarse. */
@Component
class OrderNotifications implements NotificationRenderer {

	static final String TYPE = "ORDER_CONFIRMATION";
	private static final Logger log = LoggerFactory.getLogger(OrderNotifications.class);

	private final NotificationService notifications;
	private final OrderService orders;
	private final UserDirectory users;
	private final String frontendUrl;

	OrderNotifications(NotificationService notifications, OrderService orders, UserDirectory users,
			@Value("${app.frontend-url}") String frontendUrl) {
		this.notifications = notifications;
		this.orders = orders;
		this.users = users;
		this.frontendUrl = frontendUrl;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void onOrderPaid(OrderPaid event) {
		try {
			OrderSummary order = orders.summaryOf(event.orderId()).orElse(null);
			var buyer = order == null ? null : users.contactOf(order.buyerId()).orElse(null);
			if (buyer == null) {
				return;
			}
			notifications.enqueue(new NotificationService.Request(TYPE, buyer.email(), buyer.id(), order.organizationId(), order.eventId(),
					Map.of("orderId", order.orderId()), "order-confirmation:" + order.orderId(), null));
		} catch (RuntimeException e) {
			log.error("No se pudo encolar la confirmación de la orden {}", event.orderId(), e);
		}
	}

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public Rendered render(Map<String, String> params) {
		OrderSummary order = orders.summaryOf(params.get("orderId")).orElseThrow(() -> new SkipNotificationException("La orden ya no existe"));
		if (order.status() != OrderStatus.PAID) {
			throw new SkipNotificationException("La orden ya no está pagada (" + order.status() + ")");
		}
		String name = users.contactOf(order.buyerId()).map(UserDirectory.Contact::displayName).orElse("");
		boolean free = order.totalMinor() == 0;
		String subject = (free ? "Reserva confirmada: " : "Confirmación de compra: ") + order.eventTitle();
		String url = frontendUrl + "/orders/" + order.orderId();

		StringBuilder text = new StringBuilder("Hola ").append(name).append(",\n\n")
				.append(free ? "Tu reserva está confirmada." : "Recibimos tu pago. ¡Gracias por tu compra!").append("\n\n").append(order.eventTitle()).append("\n");
		Rows rows = new Rows();
		for (var line : order.lines()) {
			String amount = EmailFormat.money(line.unitPriceMinor() * line.quantity(), order.currency());
			text.append("  ").append(line.quantity()).append(" × ").append(line.name()).append(" — ").append(amount).append('\n');
			rows.add(line.quantity() + " × " + line.name(), amount);
		}
		text.append('\n');
		if (!free) {
			text.append("Subtotal: ").append(EmailFormat.money(order.subtotalMinor(), order.currency())).append('\n');
			rows.add("Subtotal", EmailFormat.money(order.subtotalMinor(), order.currency()));
			if (order.feePaidByBuyer() && order.feeMinor() > 0) {
				text.append("Cargo por servicio: ").append(EmailFormat.money(order.feeMinor(), order.currency())).append('\n');
				rows.add("Cargo por servicio", EmailFormat.money(order.feeMinor(), order.currency()));
			}
			text.append("Total: ").append(EmailFormat.money(order.totalMinor(), order.currency())).append('\n');
			rows.add("Total", EmailFormat.money(order.totalMinor(), order.currency()));
		}
		text.append("\nTus boletos llegan en otro correo y siempre están en Mis boletos. Detalle de la orden:\n").append(url).append('\n');

		String html = EmailLayout.wrap(subject,
				EmailLayout.h1(free ? "Reserva confirmada" : "¡Gracias por tu compra!") + EmailLayout.p("Hola " + name + ", " + (free ? "tu reserva está confirmada." : "recibimos tu pago."))
						+ EmailLayout.p(order.eventTitle()) + EmailLayout.rows(rows.toArray(new String[0]))
						+ EmailLayout.p("Tus boletos llegan en otro correo y siempre están disponibles en Mis boletos.")
						+ EmailLayout.button(url, "Ver mi orden"));
		return new Rendered(subject, text.toString(), html);
	}

	/** Acumula pares etiqueta/valor sin repetir código. */
	private static final class Rows extends java.util.ArrayList<String> {
		private static final long serialVersionUID = 1L;

		void add(String label, String value) {
			super.add(label);
			super.add(value);
		}
	}
}
