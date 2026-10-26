package api.joinify.event;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import api.joinify.event.EventRepository.EventRow;
import api.joinify.organization.OrganizationAccess;

/** API pública del módulo de eventos para quien necesita describir un evento y sus entradas (boletos, validación). */
@Service
public class EventCatalog {

	public record TicketTypeInfo(String id, String eventId, String name, boolean nominative, AccessRule rule) {
	}

	public record SessionInfo(String id, String name, Instant startsAt, Instant endsAt, String venueName, String venueAddress,
			String venueCity) {
	}

	public record EventDetails(String id, String organizationId, EventStatus status, String slug, String title, String timezone,
			String organizationName, String cancelReason, List<SessionInfo> sessions) {
	}

	private final EventRepository events;
	private final TicketTypeRepository ticketTypes;
	private final OrganizationAccess organizations;

	EventCatalog(EventRepository events, TicketTypeRepository ticketTypes, OrganizationAccess organizations) {
		this.events = events;
		this.ticketTypes = ticketTypes;
		this.organizations = organizations;
	}

	public TicketTypeInfo ticketType(String ticketTypeId) {
		TicketTypeRepository.Row row = ticketTypes.findById(ticketTypeId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Entrada no encontrada"));
		return new TicketTypeInfo(row.id(), row.eventId(), row.name(), row.nominative(),
				new AccessRule(row.scope(), ticketTypes.sessionIds(row.id()), row.maxTotalUses(), row.maxUsesPerSession(),
						row.earlyEntryMinutes()));
	}

	public EventDetails event(String eventId) {
		EventRow event = events.findEventById(eventId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Evento no encontrado"));
		return new EventDetails(event.id(), event.organizationId(), event.status(), event.slug(), event.title(), event.timezone(),
				organizations.nameOf(event.organizationId()), event.cancelReason(),
				events.findSessions(event.id()).stream()
						.map(s -> new SessionInfo(s.id(), s.name(), s.startsAt().toInstant(ZoneOffset.UTC),
								s.endsAt().toInstant(ZoneOffset.UTC), s.venueName(), s.venueAddress(), s.venueCity()))
						.toList());
	}
}
