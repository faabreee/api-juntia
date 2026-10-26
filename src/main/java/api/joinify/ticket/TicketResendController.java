package api.joinify.ticket;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import api.joinify.identity.AuthUser;
import api.joinify.notification.NotificationService;
import api.joinify.order.OrderService;
import api.joinify.organization.OrganizationAccess;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Reenvío del correo con los boletos: lo pide el comprador (con límite) o el organizador desde su panel. */
@RestController
@Tag(name = "Boletos")
class TicketResendController {

	private static final int BUYER_LIMIT_PER_HOUR = 3;
	private static final int ORGANIZER_LIMIT_PER_HOUR = 10;

	private final TicketNotifications notifications;
	private final NotificationService queue;
	private final TicketService tickets;
	private final OrderService orders;
	private final OrganizationAccess access;
	private final Clock clock;

	TicketResendController(TicketNotifications notifications, NotificationService queue, TicketService tickets, OrderService orders,
			OrganizationAccess access, Clock clock) {
		this.notifications = notifications;
		this.queue = queue;
		this.tickets = tickets;
		this.orders = orders;
		this.access = access;
		this.clock = clock;
	}

	/** El comprador pide que le reenvíen sus boletos (máximo 3 por hora y orden). */
	@PostMapping("/api/orders/{orderId}/resend-tickets")
	@ResponseStatus(HttpStatus.ACCEPTED)
	void resendMyTickets(@AuthenticationPrincipal AuthUser user, @PathVariable String orderId) {
		orders.payableOf(user, orderId); // una orden ajena responde 404
		resend(orderId, BUYER_LIMIT_PER_HOUR);
	}

	/** El organizador reenvía los boletos de una orden de su organización (máximo 10 por hora y orden). */
	@PostMapping("/api/organizations/{organizationId}/orders/{orderId}/resend-tickets")
	@ResponseStatus(HttpStatus.ACCEPTED)
	void resendTicketsAsOrganizer(@AuthenticationPrincipal AuthUser user, @PathVariable String organizationId, @PathVariable String orderId) {
		access.requireManager(organizationId, user.getId());
		if (!orders.payableOf(orderId).organizationId().equals(organizationId)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Orden no encontrada");
		}
		resend(orderId, ORGANIZER_LIMIT_PER_HOUR);
	}

	private void resend(String orderId, int limitPerHour) {
		if (tickets.detailsOfOrder(orderId).isEmpty()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "La orden no tiene boletos vigentes para reenviar");
		}
		long recent = queue.countRecent(TicketNotifications.DELIVERY, "resend:" + orderId + ":", clock.instant().minus(Duration.ofHours(1)));
		if (recent >= limitPerHour) {
			throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Ya se reenviaron los boletos varias veces; intenta de nuevo más tarde");
		}
		if (!notifications.enqueueDelivery(orderId, "resend:" + orderId + ":" + UUID.randomUUID())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "No se pudo determinar a quién enviar los boletos");
		}
	}
}
