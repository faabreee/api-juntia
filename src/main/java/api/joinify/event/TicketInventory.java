package api.joinify.event;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import api.joinify.event.EventRepository.EventRow;

/**
 * API pública del módulo de eventos para las ventas: qué se puede comprar y la reserva de cupo.
 *
 * El cupo se reserva con un UPDATE condicional atómico (ver {@link TicketTypeRepository#tryReserve}); estos métodos
 * se llaman dentro de la transacción de la orden, de modo que si la orden falla el cupo vuelve solo.
 */
@Service
public class TicketInventory {

	public record EventForSale(String id, String organizationId, String slug, String title) {
	}

	public record TicketForSale(String id, String name, long priceMinor, String currency) {
	}

	private final EventRepository events;
	private final TicketTypeRepository ticketTypes;

	TicketInventory(EventRepository events, TicketTypeRepository ticketTypes) {
		this.events = events;
		this.ticketTypes = ticketTypes;
	}

	/** El evento debe estar publicado y no haber terminado. Los borradores no existen para el comprador. */
	public EventForSale requireEventForSale(String slug, Instant now) {
		EventRow event = events.findPublicBySlug(slug)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Evento no encontrado"));
		if (event.status() == EventStatus.CANCELLED) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "El evento fue cancelado");
		}
		LocalDateTime utcNow = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
		if (events.findSessions(event.id()).stream().noneMatch(s -> s.endsAt().isAfter(utcNow))) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "El evento ya terminó");
		}
		return new EventForSale(event.id(), event.organizationId(), event.slug(), event.title());
	}

	/** El tipo de entrada debe ser visible, estar en periodo de venta y respetar el máximo por orden. */
	public TicketForSale requireTicketForSale(String eventId, String ticketTypeId, int quantity, Instant now) {
		TicketTypeRepository.Row type = ticketTypes.find(eventId, ticketTypeId)
				.filter(TicketTypeRepository.Row::active)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Entrada no encontrada"));
		LocalDateTime utcNow = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
		if (type.salesStart() != null && utcNow.isBefore(type.salesStart())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "La venta de «" + type.name() + "» aún no empieza");
		}
		if (type.salesEnd() != null && utcNow.isAfter(type.salesEnd())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "La venta de «" + type.name() + "» ya terminó");
		}
		if (quantity < 1 || quantity > type.maxPerOrder()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"«" + type.name() + "»: máximo " + type.maxPerOrder() + " por orden");
		}
		return new TicketForSale(type.id(), type.name(), type.priceMinor(), type.currency());
	}

	/** Reserva cupo o lanza 409 "agotado". Solo válido dentro de una transacción de orden. */
	public void reserve(TicketForSale ticket, int quantity) {
		if (!ticketTypes.tryReserve(ticket.id(), quantity)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Agotado: «" + ticket.name() + "» no tiene cupo suficiente");
		}
	}

	/** Reserva cupo sin lanzar excepción: la usa el pago tardío para reabrir una orden expirada si aún hay cupo. */
	public boolean tryReserve(String ticketTypeId, int quantity) {
		return ticketTypes.tryReserve(ticketTypeId, quantity);
	}

	/** Devuelve cupo (orden expirada o cancelada). */
	public void release(String ticketTypeId, int quantity) {
		ticketTypes.release(ticketTypeId, quantity);
	}
}
