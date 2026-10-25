package api.joinify.event;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import api.joinify.event.EventRepository.EventRow;
import api.joinify.event.EventRepository.SessionRow;
import api.joinify.event.EventRepository.SummaryRow;
import api.joinify.event.EventRepository.VenueRow;
import jakarta.validation.constraints.NotNull;

/** Respuestas de la API de eventos. Los campos con @NotNull salen como obligatorios en el OpenAPI. */
final class EventDtos {

	private EventDtos() {
	}

	record VenueResponse(@NotNull String id, @NotNull String name, String address, String city, @NotNull String country,
			Integer capacity) {
		static VenueResponse of(VenueRow v) {
			return new VenueResponse(v.id(), v.name(), v.address(), v.city(), v.country(), v.capacity());
		}
	}

	record SessionResponse(@NotNull String id, @NotNull String name, @NotNull Instant startsAt, @NotNull Instant endsAt,
			VenueResponse venue) {
		static SessionResponse of(SessionRow s) {
			VenueResponse venue = s.venueId() == null ? null
					: new VenueResponse(s.venueId(), s.venueName(), s.venueAddress(), s.venueCity(), s.venueCountry(), null);
			return new SessionResponse(s.id(), s.name(), s.startsAt().toInstant(ZoneOffset.UTC),
					s.endsAt().toInstant(ZoneOffset.UTC), venue);
		}
	}

	record EventResponse(@NotNull String id, @NotNull String slug, @NotNull String title, String description,
			@NotNull EventStatus status, @NotNull String timezone, boolean hasCover, String cancelReason,
			Instant publishedAt, @NotNull List<SessionResponse> sessions) {
		static EventResponse of(EventRow e, List<SessionRow> sessions) {
			return new EventResponse(e.id(), e.slug(), e.title(), e.description(), e.status(), e.timezone(),
					e.coverKey() != null, e.cancelReason(),
					e.publishedAt() == null ? null : e.publishedAt().toInstant(ZoneOffset.UTC),
					sessions.stream().map(SessionResponse::of).toList());
		}
	}

	record EventSummaryResponse(@NotNull String id, @NotNull String slug, @NotNull String title,
			@NotNull EventStatus status, boolean hasCover, int sessionCount, Instant firstSessionAt,
			Instant lastSessionAt) {
		static EventSummaryResponse of(SummaryRow r) {
			return new EventSummaryResponse(r.id(), r.slug(), r.title(), r.status(), r.hasCover(), r.sessionCount(),
					r.firstSessionAt() == null ? null : r.firstSessionAt().toInstant(ZoneOffset.UTC),
					r.lastSessionAt() == null ? null : r.lastSessionAt().toInstant(ZoneOffset.UTC));
		}
	}

	record EventPageResponse(@NotNull List<EventSummaryResponse> items, int page, int size, long total) {
	}

	record PublicEventResponse(@NotNull String slug, @NotNull String title, String description,
			@NotNull EventStatus status, @NotNull String timezone, @NotNull String organizationName, String coverUrl,
			String cancelReason, @NotNull List<SessionResponse> sessions) {
	}
}
