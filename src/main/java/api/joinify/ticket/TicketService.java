package api.joinify.ticket;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import api.joinify.event.AccessRule;
import api.joinify.event.EventCatalog;
import api.joinify.event.EventCatalog.EventDetails;
import api.joinify.event.EventCatalog.SessionInfo;
import api.joinify.event.EventCatalog.TicketTypeInfo;
import api.joinify.identity.AuthUser;
import api.joinify.ticket.TicketRepository.Status;
import api.joinify.ticket.TicketRepository.TicketRow;

/** Consulta de boletos por su dueño. Un boleto ajeno se comporta como inexistente. */
@Service
class TicketService {

	/** Boleto listo para mostrar: incluye el QR firmado (solo si sigue vigente). */
	record TicketView(TicketRow ticket, String qrToken) {
	}

	/** Boleto con el detalle del evento y de lo que cubre, para la vista completa y el PDF. */
	record TicketDetail(TicketView view, EventDetails event, List<SessionInfo> coveredSessions, AccessRule rule) {
	}

	private static final int MAX_ATTENDEE_NAME = 120;

	private final TicketRepository repository;
	private final TicketTokenService tokens;
	private final EventCatalog catalog;

	TicketService(TicketRepository repository, TicketTokenService tokens, EventCatalog catalog) {
		this.repository = repository;
		this.tokens = tokens;
		this.catalog = catalog;
	}

	List<TicketView> mine(AuthUser owner) {
		return repository.findByOwner(owner.getId()).stream().map(this::view).toList();
	}

	List<TicketView> ofOrder(AuthUser owner, String orderId) {
		return repository.findByOrder(orderId, owner.getId()).stream().map(this::view).toList();
	}

	TicketDetail detail(AuthUser owner, String ticketId) {
		return detailOf(owned(owner, ticketId));
	}

	/** Detalle de todos los boletos de una orden (PDF y correo). */
	List<TicketDetail> detailsOfOrder(String orderId) {
		return repository.findAllByOrder(orderId).stream().filter(t -> t.status() == Status.VALID).map(this::detailOf).toList();
	}

	List<TicketDetail> detailsOfOrder(AuthUser owner, String orderId) {
		return repository.findByOrder(orderId, owner.getId()).stream().filter(t -> t.status() == Status.VALID).map(this::detailOf).toList();
	}

	TicketView setAttendee(AuthUser owner, String ticketId, String rawName) {
		TicketRow ticket = owned(owner, ticketId);
		if (!ticket.nominative()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Este boleto no es nominativo");
		}
		if (ticket.status() != Status.VALID) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Este boleto ya no es válido");
		}
		String name = rawName == null ? "" : rawName.trim().replaceAll("\\s+", " ");
		if (name.length() < 2 || name.length() > MAX_ATTENDEE_NAME) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El nombre debe tener entre 2 y " + MAX_ATTENDEE_NAME + " caracteres");
		}
		if (repository.hasCheckedIn(ticketId)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "El boleto ya se usó para ingresar: el nombre no se puede cambiar");
		}
		repository.updateAttendee(ticketId, name);
		return view(owned(owner, ticketId));
	}

	private TicketRow owned(AuthUser owner, String ticketId) {
		return repository.findOwned(ticketId, owner.getId())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Boleto no encontrado"));
	}

	private TicketView view(TicketRow ticket) {
		return new TicketView(ticket, ticket.status() == Status.VALID ? tokens.issue(ticket.id(), ticket.eventId()) : null);
	}

	private TicketDetail detailOf(TicketRow ticket) {
		EventDetails event = catalog.event(ticket.eventId());
		TicketTypeInfo type = catalog.ticketType(ticket.ticketTypeId());
		List<SessionInfo> covered = event.sessions().stream().filter(s -> type.rule().covers(s.id())).toList();
		return new TicketDetail(view(ticket), event, covered, type.rule());
	}

	static Instant utc(java.time.LocalDateTime time) {
		return time == null ? null : time.toInstant(ZoneOffset.UTC);
	}
}
