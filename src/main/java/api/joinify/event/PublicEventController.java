package api.joinify.event;

import java.time.Duration;
import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import api.joinify.event.EventDtos.PublicEventResponse;
import api.joinify.event.EventDtos.SessionResponse;
import api.joinify.event.EventRepository.EventRow;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Página pública de un evento por slug. Sin autenticación; los borradores no existen para el público. */
@RestController
@RequestMapping("/api/public/events")
@Tag(name = "Eventos públicos")
class PublicEventController {

	private final EventService service;
	private final TicketTypeService ticketTypes;

	PublicEventController(EventService service, TicketTypeService ticketTypes) {
		this.service = service;
		this.ticketTypes = ticketTypes;
	}

	@GetMapping("/{slug}")
	PublicEventResponse getPublicEvent(@PathVariable String slug) {
		EventRow e = service.publicEvent(slug);
		String coverUrl = e.coverKey() == null ? null : "/api/public/events/" + e.slug() + "/cover";
		return new PublicEventResponse(e.slug(), e.title(), e.description(), e.status(), e.timezone(),
				service.organizationName(e.organizationId()), coverUrl, e.cancelReason(),
				service.sessions(e.id()).stream().map(SessionResponse::of).toList(),
				// un evento cancelado ya no vende
				e.status() == EventStatus.CANCELLED ? List.of()
						: ticketTypes.publicTypes(e.id()).stream().map(TicketTypeDtos.PublicTicketTypeResponse::of).toList());
	}

	@GetMapping("/{slug}/cover")
	ResponseEntity<org.springframework.core.io.Resource> getPublicCover(@PathVariable String slug) {
		EventService.CoverFile cover = service.publicCover(slug);
		return ResponseEntity.ok().contentType(MediaType.parseMediaType(cover.contentType()))
				.cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic()).body(cover.resource());
	}
}
