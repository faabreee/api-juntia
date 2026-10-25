package api.joinify.ticket;

import java.time.Instant;
import java.util.List;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import api.joinify.event.EventCatalog.SessionInfo;
import api.joinify.identity.AuthUser;
import api.joinify.ticket.TicketRepository.TicketRow;
import api.joinify.ticket.TicketService.TicketDetail;
import api.joinify.ticket.TicketService.TicketView;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api")
@Tag(name = "Boletos")
class TicketController {

	record TicketResponse(@NotNull String id, @NotNull String orderId, @NotNull String code, @NotNull TicketRepository.Status status,
			String attendeeName, boolean nominative, @NotNull String ticketTypeName, @NotNull String eventTitle,
			@NotNull String eventSlug, @NotNull String timezone, Instant firstSessionAt, Instant lastSessionAt,
			String qrToken) {

		static TicketResponse of(TicketView v) {
			TicketRow t = v.ticket();
			return new TicketResponse(t.id(), t.orderId(), ShortCode.format(t.code()), t.status(), t.attendeeName(), t.nominative(),
					t.ticketTypeName(), t.eventTitle(), t.eventSlug(), t.timezone(), TicketService.utc(t.firstSessionAt()),
					TicketService.utc(t.lastSessionAt()), v.qrToken());
		}
	}

	record VenueResponse(String name, String address, String city) {
	}

	record SessionResponse(@NotNull String id, @NotNull String name, @NotNull Instant startsAt, @NotNull Instant endsAt, VenueResponse venue) {
		static SessionResponse of(SessionInfo s) {
			return new SessionResponse(s.id(), s.name(), s.startsAt(), s.endsAt(),
					s.venueName() == null ? null : new VenueResponse(s.venueName(), s.venueAddress(), s.venueCity()));
		}
	}

	/** Lo que cubre el boleto: las sesiones a las que da acceso y sus límites de uso (ADR 0004). */
	record TicketDetailResponse(@NotNull TicketResponse ticket, @NotNull String organizationName,
			@NotNull List<SessionResponse> sessions, Integer maxTotalUses, Integer maxUsesPerSession, int earlyEntryMinutes) {

		static TicketDetailResponse of(TicketDetail d) {
			return new TicketDetailResponse(TicketResponse.of(d.view()), d.event().organizationName(),
					d.coveredSessions().stream().map(SessionResponse::of).toList(), d.rule().maxTotalUses(),
					d.rule().maxUsesPerSession(), d.rule().earlyEntryMinutes());
		}
	}

	record AttendeeRequest(@NotBlank @Size(min = 2, max = 120) String name) {
	}

	private final TicketService service;
	private final TicketPdfService pdf;

	TicketController(TicketService service, TicketPdfService pdf) {
		this.service = service;
		this.pdf = pdf;
	}

	/** Mis boletos, ordenados por la fecha del evento. El QR viaja firmado en cada boleto vigente. */
	@GetMapping("/tickets")
	List<TicketResponse> listMyTickets(@AuthenticationPrincipal AuthUser user) {
		return service.mine(user).stream().map(TicketResponse::of).toList();
	}

	@GetMapping("/tickets/{ticketId}")
	TicketDetailResponse getTicket(@AuthenticationPrincipal AuthUser user, @PathVariable String ticketId) {
		return TicketDetailResponse.of(service.detail(user, ticketId));
	}

	/** Registra el nombre del asistente en un boleto nominativo. */
	@PutMapping("/tickets/{ticketId}/attendee")
	TicketResponse setAttendee(@AuthenticationPrincipal AuthUser user, @PathVariable String ticketId,
			@Valid @RequestBody AttendeeRequest request) {
		return TicketResponse.of(service.setAttendee(user, ticketId, request.name()));
	}

	@GetMapping(value = "/tickets/{ticketId}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
	ResponseEntity<byte[]> downloadTicketPdf(@AuthenticationPrincipal AuthUser user, @PathVariable String ticketId) {
		TicketDetail detail = service.detail(user, ticketId);
		if (detail.view().qrToken() == null) {
			throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Este boleto ya no es válido");
		}
		return pdfResponse(pdf.render(List.of(detail)), "boleto-" + detail.view().ticket().code() + ".pdf");
	}

	@GetMapping("/orders/{orderId}/tickets")
	List<TicketResponse> listOrderTickets(@AuthenticationPrincipal AuthUser user, @PathVariable String orderId) {
		return service.ofOrder(user, orderId).stream().map(TicketResponse::of).toList();
	}

	@GetMapping(value = "/orders/{orderId}/tickets/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
	ResponseEntity<byte[]> downloadOrderTicketsPdf(@AuthenticationPrincipal AuthUser user, @PathVariable String orderId) {
		List<TicketDetail> details = service.detailsOfOrder(user, orderId);
		if (details.isEmpty()) {
			throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "No hay boletos vigentes en esta orden");
		}
		return pdfResponse(pdf.render(details), "boletos-" + orderId.substring(0, 8) + ".pdf");
	}

	private static ResponseEntity<byte[]> pdfResponse(byte[] bytes, String filename) {
		return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
				.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
				.header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(bytes);
	}
}
