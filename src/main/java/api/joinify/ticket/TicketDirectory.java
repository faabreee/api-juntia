package api.joinify.ticket;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import api.joinify.ticket.TicketRepository.Status;

/** API pública del módulo de boletos para quien necesita localizar boletos (validación en puerta). */
@Service
public class TicketDirectory {

	public record TicketInfo(String id, String eventId, String ticketTypeId, String ticketTypeName, String code, boolean valid,
			String attendeeName, boolean nominative) {
	}

	public record AttendeeMatch(String ticketId, String code, boolean valid, String ticketTypeName, String attendeeName,
			String buyerName, String buyerEmailMasked) {
	}

	private static final int MIN_QUERY = 3;
	private final TicketRepository repository;

	TicketDirectory(TicketRepository repository) {
		this.repository = repository;
	}

	public Optional<TicketInfo> find(String ticketId) {
		return repository.findById(ticketId).map(TicketDirectory::info);
	}

	/** Busca por código corto dentro de un evento (el código ya viene normalizado). */
	public Optional<TicketInfo> findByCode(String eventId, String normalizedCode) {
		return repository.findByEventAndCode(eventId, normalizedCode).map(TicketDirectory::info);
	}

	/** Bloquea la fila del boleto hasta el fin de la transacción: serializa a las puertas que leen el mismo boleto. */
	public void lockForCheckIn(String ticketId) {
		repository.lockTicket(ticketId);
	}

	/** Marca el primer ingreso: desde ahí el nombre del asistente queda fijo. */
	public void markEntered(String ticketId, LocalDateTime when) {
		repository.markFirstCheckIn(ticketId, when);
	}

	public long countValidTickets(String eventId) {
		return repository.countValidByEvent(eventId);
	}

	/** Búsqueda manual en puerta por código, nombre del asistente, nombre o correo del comprador. */
	public List<AttendeeMatch> search(String eventId, String query, int limit) {
		String q = query == null ? "" : query.trim();
		if (q.length() < MIN_QUERY) {
			throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
					"Escribe al menos " + MIN_QUERY + " caracteres");
		}
		String code = ShortCode.normalize(q).orElse(null);
		return repository.search(eventId, q, code, Math.min(Math.max(limit, 1), 30));
	}

	private static TicketInfo info(TicketRepository.TicketRow t) {
		return new TicketInfo(t.id(), t.eventId(), t.ticketTypeId(), t.ticketTypeName(), t.code(), t.status() == Status.VALID,
				t.attendeeName(), t.nominative());
	}
}
