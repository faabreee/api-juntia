package api.joinify.event;

import java.time.Instant;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import api.joinify.event.EventDtos.EventPageResponse;
import api.joinify.event.EventDtos.EventResponse;
import api.joinify.event.EventDtos.EventSummaryResponse;
import api.joinify.event.EventDtos.SessionResponse;
import api.joinify.event.EventDtos.VenueResponse;
import api.joinify.event.EventRepository.EventRow;
import api.joinify.identity.AuthUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/organizations/{organizationId}")
@Tag(name = "Eventos")
class EventController {

	record VenueRequest(@NotBlank @Size(max = 150) String name, @Size(max = 255) String address,
			@Size(max = 100) String city, @NotBlank @Pattern(regexp = "[A-Za-z]{2}") String country,
			@Min(1) Integer capacity) {
	}

	record EventRequest(@NotBlank @Size(min = 3, max = 150) String title, @Size(max = 10000) String description,
			@NotBlank @Size(max = 64) String timezone) {
	}

	record SessionRequest(@NotBlank @Size(max = 120) String name, @NotNull Instant startsAt, @NotNull Instant endsAt,
			String venueId) {
		EventService.SessionInput toInput() {
			return new EventService.SessionInput(name, startsAt, endsAt, venueId);
		}
	}

	record CancelRequest(@Size(max = 500) String reason) {
	}

	private final EventService service;

	EventController(EventService service) {
		this.service = service;
	}

	private EventResponse toResponse(EventRow event) {
		return EventResponse.of(event, service.sessions(event.id()));
	}

	// ---- venues ----

	@PostMapping("/venues")
	@ResponseStatus(HttpStatus.CREATED)
	VenueResponse createVenue(@AuthenticationPrincipal AuthUser user, @PathVariable String organizationId,
			@Valid @RequestBody VenueRequest r) {
		return VenueResponse.of(service.createVenue(user, organizationId, r.name(), r.address(), r.city(), r.country(), r.capacity()));
	}

	@GetMapping("/venues")
	java.util.List<VenueResponse> listVenues(@AuthenticationPrincipal AuthUser user, @PathVariable String organizationId) {
		return service.venues(user, organizationId).stream().map(VenueResponse::of).toList();
	}

	@PutMapping("/venues/{venueId}")
	VenueResponse updateVenue(@AuthenticationPrincipal AuthUser user, @PathVariable String organizationId,
			@PathVariable String venueId, @Valid @RequestBody VenueRequest r) {
		return VenueResponse.of(service.updateVenue(user, organizationId, venueId, r.name(), r.address(), r.city(), r.country(), r.capacity()));
	}

	@DeleteMapping("/venues/{venueId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void deleteVenue(@AuthenticationPrincipal AuthUser user, @PathVariable String organizationId, @PathVariable String venueId) {
		service.deleteVenue(user, organizationId, venueId);
	}

	// ---- events ----

	@PostMapping("/events")
	@ResponseStatus(HttpStatus.CREATED)
	EventResponse createEvent(@AuthenticationPrincipal AuthUser user, @PathVariable String organizationId,
			@Valid @RequestBody EventRequest r) {
		return toResponse(service.createEvent(user, organizationId, r.title(), r.description(), r.timezone()));
	}

	/** Eventos con alguna sesión dentro del rango [from, to). */
	@GetMapping("/events")
	EventPageResponse listEvents(@AuthenticationPrincipal AuthUser user, @PathVariable String organizationId,
			@RequestParam(required = false) EventStatus status, @RequestParam(required = false) String q,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
			@RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		EventService.Page<EventRepository.SummaryRow> result = service.list(user, organizationId, status, q, from, to, page, size);
		return new EventPageResponse(result.items().stream().map(EventSummaryResponse::of).toList(), result.page(),
				result.size(), result.total());
	}

	@GetMapping("/events/{eventId}")
	EventResponse getEvent(@AuthenticationPrincipal AuthUser user, @PathVariable String organizationId,
			@PathVariable String eventId) {
		return toResponse(service.get(user, organizationId, eventId));
	}

	@PutMapping("/events/{eventId}")
	EventResponse updateEvent(@AuthenticationPrincipal AuthUser user, @PathVariable String organizationId,
			@PathVariable String eventId, @Valid @RequestBody EventRequest r) {
		return toResponse(service.update(user, organizationId, eventId, r.title(), r.description(), r.timezone()));
	}

	@DeleteMapping("/events/{eventId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void deleteEvent(@AuthenticationPrincipal AuthUser user, @PathVariable String organizationId, @PathVariable String eventId) {
		service.delete(user, organizationId, eventId);
	}

	@PostMapping("/events/{eventId}/publish")
	EventResponse publishEvent(@AuthenticationPrincipal AuthUser user, @PathVariable String organizationId,
			@PathVariable String eventId) {
		return toResponse(service.publish(user, organizationId, eventId));
	}

	@PostMapping("/events/{eventId}/unpublish")
	EventResponse unpublishEvent(@AuthenticationPrincipal AuthUser user, @PathVariable String organizationId,
			@PathVariable String eventId) {
		return toResponse(service.unpublish(user, organizationId, eventId));
	}

	@PostMapping("/events/{eventId}/cancel")
	EventResponse cancelEvent(@AuthenticationPrincipal AuthUser user, @PathVariable String organizationId,
			@PathVariable String eventId, @Valid @RequestBody(required = false) CancelRequest r) {
		return toResponse(service.cancel(user, organizationId, eventId, r == null ? null : r.reason()));
	}

	// ---- sessions ----

	@PostMapping("/events/{eventId}/sessions")
	@ResponseStatus(HttpStatus.CREATED)
	SessionResponse addSession(@AuthenticationPrincipal AuthUser user, @PathVariable String organizationId,
			@PathVariable String eventId, @Valid @RequestBody SessionRequest r) {
		return SessionResponse.of(service.addSession(user, organizationId, eventId, r.toInput()));
	}

	@PutMapping("/events/{eventId}/sessions/{sessionId}")
	SessionResponse updateSession(@AuthenticationPrincipal AuthUser user, @PathVariable String organizationId,
			@PathVariable String eventId, @PathVariable String sessionId, @Valid @RequestBody SessionRequest r) {
		return SessionResponse.of(service.updateSession(user, organizationId, eventId, sessionId, r.toInput()));
	}

	@DeleteMapping("/events/{eventId}/sessions/{sessionId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void deleteSession(@AuthenticationPrincipal AuthUser user, @PathVariable String organizationId,
			@PathVariable String eventId, @PathVariable String sessionId) {
		service.deleteSession(user, organizationId, eventId, sessionId);
	}

	// ---- cover ----

	@PostMapping(value = "/events/{eventId}/cover", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	EventResponse uploadCover(@AuthenticationPrincipal AuthUser user, @PathVariable String organizationId,
			@PathVariable String eventId, @RequestParam("file") MultipartFile file) {
		return toResponse(service.setCover(user, organizationId, eventId, file));
	}

	@DeleteMapping("/events/{eventId}/cover")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void removeCover(@AuthenticationPrincipal AuthUser user, @PathVariable String organizationId, @PathVariable String eventId) {
		service.removeCover(user, organizationId, eventId);
	}

	@GetMapping("/events/{eventId}/cover")
	ResponseEntity<org.springframework.core.io.Resource> getCover(@AuthenticationPrincipal AuthUser user,
			@PathVariable String organizationId, @PathVariable String eventId) {
		EventService.CoverFile cover = service.cover(user, organizationId, eventId);
		return ResponseEntity.ok().contentType(MediaType.parseMediaType(cover.contentType()))
				.cacheControl(CacheControl.noCache().cachePrivate()).body(cover.resource());
	}
}
