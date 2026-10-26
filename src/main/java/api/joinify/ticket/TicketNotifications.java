package api.joinify.ticket;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import api.joinify.event.EventCancelled;
import api.joinify.event.EventCatalog;
import api.joinify.event.EventScheduleChanged;
import api.joinify.identity.UserDirectory;
import api.joinify.notification.NotificationService;
import api.joinify.notification.NotificationService.Request;
import api.joinify.order.OrderService;

/**
 * Productor de las notificaciones que dependen de los boletos: entrega, recordatorio, evento cancelado y evento
 * modificado. Solo encola; el correo se arma al enviarse (ver los renderizadores de este paquete).
 */
@Component
class TicketNotifications {

	static final String DELIVERY = "TICKETS_DELIVERY";
	static final String REMINDER = "EVENT_REMINDER";
	static final String CANCELLED = "EVENT_CANCELLED";
	static final String CHANGED = "EVENT_CHANGED";

	/** Los cambios se agrupan: si el organizador edita varias cosas seguidas, cada titular recibe un solo correo. */
	static final Duration CHANGE_DELAY = Duration.ofMinutes(10);
	private static final Logger log = LoggerFactory.getLogger(TicketNotifications.class);

	private final NotificationService notifications;
	private final TicketRepository repository;
	private final EventCatalog catalog;
	private final OrderService orders;
	private final UserDirectory users;
	private final Clock clock;

	TicketNotifications(NotificationService notifications, TicketRepository repository, EventCatalog catalog, OrderService orders,
			UserDirectory users, Clock clock) {
		this.notifications = notifications;
		this.repository = repository;
		this.catalog = catalog;
		this.orders = orders;
		this.users = users;
		this.clock = clock;
	}

	/** Se emitieron boletos: se encola el correo con los PDF (una sola vez por orden). */
	@EventListener
	void onTicketsIssued(TicketsIssued event) {
		try {
			enqueueDelivery(event.orderId(), "tickets:" + event.orderId());
		} catch (RuntimeException e) {
			log.error("No se pudo encolar la entrega de boletos de la orden {}", event.orderId(), e);
		}
	}

	/** Encola la entrega de los boletos de una orden. {@code dedupeKey} nulo = reenvío (siempre sale). */
	boolean enqueueDelivery(String orderId, String dedupeKey) {
		var order = orders.summaryOf(orderId).orElse(null);
		var buyer = order == null ? null : users.contactOf(order.buyerId()).orElse(null);
		if (buyer == null) {
			return false;
		}
		return notifications.enqueue(new Request(DELIVERY, buyer.email(), buyer.id(), order.organizationId(), order.eventId(),
				Map.of("orderId", orderId), dedupeKey, null)).isPresent();
	}

	@org.springframework.transaction.event.TransactionalEventListener(phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void onEventCancelled(EventCancelled event) {
		try {
			var details = catalog.event(event.eventId());
			for (var holder : repository.holdersOfEvent(event.eventId())) {
				notifications.enqueue(new Request(CANCELLED, holder.email(), holder.userId(), details.organizationId(), event.eventId(),
						Map.of("eventId", event.eventId(), "userId", holder.userId()), "event-cancelled:" + event.eventId() + ":" + holder.userId(), null));
			}
		} catch (RuntimeException e) {
			log.error("No se pudo avisar de la cancelación del evento {}", event.eventId(), e);
		}
	}

	@org.springframework.transaction.event.TransactionalEventListener(phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void onEventChanged(EventScheduleChanged event) {
		try {
			var details = catalog.event(event.eventId());
			Instant now = clock.instant();
			long bucket = now.getEpochSecond() / CHANGE_DELAY.toSeconds();
			for (var holder : repository.holdersOfEvent(event.eventId())) {
				// Un correo por titular y por ventana de 10 minutos; el contenido se arma al enviar, con el estado final.
				notifications.enqueue(new Request(CHANGED, holder.email(), holder.userId(), details.organizationId(), event.eventId(),
						Map.of("eventId", event.eventId(), "userId", holder.userId()),
						"event-changed:" + event.eventId() + ":" + holder.userId() + ":" + bucket, now.plus(CHANGE_DELAY)));
			}
		} catch (RuntimeException e) {
			log.error("No se pudo avisar del cambio del evento {}", event.eventId(), e);
		}
	}

	/** Cuántas horas antes de una sesión sale el recordatorio. */
	static final int REMINDER_HOURS = 24;
	/** Quien compró hace menos de esto ya recibió su confirmación: no se le recuerda al instante. */
	static final Duration REMINDER_MIN_AGE = Duration.ofHours(3);

	/** Encola los recordatorios de las sesiones que empiezan en las próximas 24 h. Devuelve cuántos encoló. */
	int enqueueReminders() {
		Instant now = clock.instant();
		var candidates = repository.reminderCandidates(java.time.LocalDateTime.ofInstant(now, java.time.ZoneOffset.UTC),
				java.time.LocalDateTime.ofInstant(now.plus(Duration.ofHours(REMINDER_HOURS)), java.time.ZoneOffset.UTC),
				java.time.LocalDateTime.ofInstant(now.minus(REMINDER_MIN_AGE), java.time.ZoneOffset.UTC));
		int queued = 0;
		for (var c : candidates) {
			// Un recordatorio por persona, evento y día (en la zona del evento): aunque haya varias sesiones ese día.
			String date = c.sessionStart().atZone(ZoneId.of(c.timezone())).toLocalDate().toString();
			var id = notifications.enqueue(new Request(REMINDER, c.email(), c.userId(), catalog.event(c.eventId()).organizationId(), c.eventId(),
					Map.of("eventId", c.eventId(), "userId", c.userId(), "date", date), "reminder:" + c.eventId() + ":" + c.userId() + ":" + date, null));
			if (id.isPresent()) {
				queued++;
			}
		}
		return queued;
	}

	List<TicketRepository.Holder> holders(String eventId) {
		return repository.holdersOfEvent(eventId);
	}
}
