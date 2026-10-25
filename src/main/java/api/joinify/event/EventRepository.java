package api.joinify.event;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class EventRepository {

	record VenueRow(String id, String organizationId, String name, String address, String city, String country,
			Integer capacity) {
	}

	record EventRow(String id, String organizationId, String slug, String title, String description,
			EventStatus status, String timezone, String coverKey, String coverContentType, String cancelReason,
			LocalDateTime publishedAt, LocalDateTime cancelledAt) {
	}

	record SessionRow(String id, String eventId, String name, LocalDateTime startsAt, LocalDateTime endsAt,
			String venueId, String venueName, String venueAddress, String venueCity, String venueCountry) {
	}

	record SummaryRow(String id, String slug, String title, EventStatus status, boolean hasCover, int sessionCount,
			LocalDateTime firstSessionAt, LocalDateTime lastSessionAt) {
	}

	private static final String EVENT_COLUMNS = "id, organization_id, slug, title, description, status, timezone, cover_key, cover_content_type, cancel_reason, published_at, cancelled_at";

	private final JdbcClient jdbc;

	EventRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	private static LocalDateTime now() {
		return LocalDateTime.now(ZoneOffset.UTC);
	}

	// ---- venues ----

	private static VenueRow mapVenue(ResultSet rs, int i) throws SQLException {
		return new VenueRow(rs.getString("id"), rs.getString("organization_id"), rs.getString("name"),
				rs.getString("address"), rs.getString("city"), rs.getString("country"),
				rs.getObject("capacity", Integer.class));
	}

	String insertVenue(String orgId, String name, String address, String city, String country, Integer capacity) {
		String id = UUID.randomUUID().toString();
		jdbc.sql("INSERT INTO venues (id, organization_id, name, address, city, country, capacity, created_at) VALUES (:id, :org, :name, :address, :city, :country, :capacity, :now)")
				.param("id", id).param("org", orgId).param("name", name).param("address", address)
				.param("city", city).param("country", country).param("capacity", capacity).param("now", now()).update();
		return id;
	}

	List<VenueRow> findVenues(String orgId) {
		return jdbc.sql("SELECT * FROM venues WHERE organization_id = :org ORDER BY name").param("org", orgId)
				.query(EventRepository::mapVenue).list();
	}

	Optional<VenueRow> findVenue(String orgId, String venueId) {
		return jdbc.sql("SELECT * FROM venues WHERE id = :id AND organization_id = :org").param("id", venueId)
				.param("org", orgId).query(EventRepository::mapVenue).optional();
	}

	void updateVenue(String venueId, String name, String address, String city, String country, Integer capacity) {
		jdbc.sql("UPDATE venues SET name = :name, address = :address, city = :city, country = :country, capacity = :capacity WHERE id = :id")
				.param("name", name).param("address", address).param("city", city).param("country", country)
				.param("capacity", capacity).param("id", venueId).update();
	}

	boolean venueInUse(String venueId) {
		return jdbc.sql("SELECT COUNT(*) FROM event_sessions WHERE venue_id = :id").param("id", venueId)
				.query(Long.class).single() > 0;
	}

	void deleteVenue(String venueId) {
		jdbc.sql("DELETE FROM venues WHERE id = :id").param("id", venueId).update();
	}

	// ---- events ----

	private static EventRow mapEvent(ResultSet rs, int i) throws SQLException {
		return new EventRow(rs.getString("id"), rs.getString("organization_id"), rs.getString("slug"),
				rs.getString("title"), rs.getString("description"), EventStatus.valueOf(rs.getString("status")),
				rs.getString("timezone"), rs.getString("cover_key"), rs.getString("cover_content_type"),
				rs.getString("cancel_reason"), rs.getObject("published_at", LocalDateTime.class),
				rs.getObject("cancelled_at", LocalDateTime.class));
	}

	boolean slugExists(String slug) {
		return jdbc.sql("SELECT COUNT(*) FROM events WHERE slug = :slug").param("slug", slug).query(Long.class).single() > 0;
	}

	String insertEvent(String orgId, String slug, String title, String description, String timezone) {
		String id = UUID.randomUUID().toString();
		LocalDateTime now = now();
		jdbc.sql("INSERT INTO events (id, organization_id, slug, title, description, status, timezone, created_at, updated_at) VALUES (:id, :org, :slug, :title, :description, 'DRAFT', :tz, :now, :now)")
				.param("id", id).param("org", orgId).param("slug", slug).param("title", title)
				.param("description", description).param("tz", timezone).param("now", now).update();
		return id;
	}

	Optional<EventRow> findEvent(String orgId, String eventId) {
		return jdbc.sql("SELECT " + EVENT_COLUMNS + " FROM events WHERE id = :id AND organization_id = :org")
				.param("id", eventId).param("org", orgId).query(EventRepository::mapEvent).optional();
	}

	Optional<EventRow> findPublicBySlug(String slug) {
		return jdbc.sql("SELECT " + EVENT_COLUMNS + " FROM events WHERE slug = :slug AND status IN ('PUBLISHED', 'CANCELLED')")
				.param("slug", slug).query(EventRepository::mapEvent).optional();
	}

	void updateEvent(String eventId, String title, String description, String timezone) {
		jdbc.sql("UPDATE events SET title = :title, description = :description, timezone = :tz, updated_at = :now WHERE id = :id")
				.param("title", title).param("description", description).param("tz", timezone).param("now", now())
				.param("id", eventId).update();
	}

	void updateStatus(String eventId, EventStatus status, String cancelReason) {
		LocalDateTime now = now();
		jdbc.sql("UPDATE events SET status = :status, updated_at = :now, published_at = CASE WHEN :status = 'PUBLISHED' THEN :now ELSE published_at END, cancelled_at = CASE WHEN :status = 'CANCELLED' THEN :now ELSE cancelled_at END, cancel_reason = CASE WHEN :status = 'CANCELLED' THEN :reason ELSE cancel_reason END WHERE id = :id")
				.param("status", status.name()).param("now", now).param("reason", cancelReason).param("id", eventId).update();
	}

	void updateCover(String eventId, String key, String contentType) {
		jdbc.sql("UPDATE events SET cover_key = :key, cover_content_type = :type, updated_at = :now WHERE id = :id")
				.param("key", key).param("type", contentType).param("now", now()).param("id", eventId).update();
	}

	void deleteEvent(String eventId) {
		jdbc.sql("DELETE FROM events WHERE id = :id").param("id", eventId).update();
	}

	// ---- listing ----

	record Filter(EventStatus status, String query, LocalDateTime from, LocalDateTime to) {
	}

	List<SummaryRow> search(String orgId, Filter f, int limit, int offset) {
		Map<String, Object> params = new HashMap<>();
		String sql = "SELECT e.id, e.slug, e.title, e.status, (e.cover_key IS NOT NULL) AS has_cover, COUNT(s.id) AS session_count, MIN(s.starts_at) AS first_at, MAX(s.ends_at) AS last_at, e.created_at "
				+ filterSql(orgId, f, params)
				+ " ORDER BY COALESCE(MIN(s.starts_at), e.created_at) DESC, e.id LIMIT :limit OFFSET :offset";
		params.put("limit", limit);
		params.put("offset", offset);
		return jdbc.sql(sql).params(params).query((rs, i) -> new SummaryRow(rs.getString("id"), rs.getString("slug"),
				rs.getString("title"), EventStatus.valueOf(rs.getString("status")), rs.getBoolean("has_cover"),
				rs.getInt("session_count"), rs.getObject("first_at", LocalDateTime.class),
				rs.getObject("last_at", LocalDateTime.class))).list();
	}

	long count(String orgId, Filter f) {
		Map<String, Object> params = new HashMap<>();
		String sql = "SELECT COUNT(*) FROM (SELECT e.id " + filterSql(orgId, f, params) + ") t";
		return jdbc.sql(sql).params(params).query(Long.class).single();
	}

	private static String filterSql(String orgId, Filter f, Map<String, Object> params) {
		StringBuilder sql = new StringBuilder("FROM events e LEFT JOIN event_sessions s ON s.event_id = e.id WHERE e.organization_id = :org");
		params.put("org", orgId);
		if (f.status() != null) {
			sql.append(" AND e.status = :status");
			params.put("status", f.status().name());
		}
		if (f.query() != null && !f.query().isBlank()) {
			sql.append(" AND e.title LIKE :q ESCAPE '\\\\'");
			params.put("q", "%" + f.query().trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
		}
		sql.append(" GROUP BY e.id");
		List<String> having = new ArrayList<>();
		// Eventos con alguna sesión dentro del rango [from, to)
		if (f.from() != null) {
			having.add("MAX(s.ends_at) >= :from");
			params.put("from", f.from());
		}
		if (f.to() != null) {
			having.add("MIN(s.starts_at) < :to");
			params.put("to", f.to());
		}
		if (!having.isEmpty()) {
			sql.append(" HAVING ").append(String.join(" AND ", having));
		}
		return sql.toString();
	}

	// ---- sessions ----

	private static SessionRow mapSession(ResultSet rs, int i) throws SQLException {
		return new SessionRow(rs.getString("id"), rs.getString("event_id"), rs.getString("name"),
				rs.getObject("starts_at", LocalDateTime.class), rs.getObject("ends_at", LocalDateTime.class),
				rs.getString("venue_id"), rs.getString("venue_name"), rs.getString("venue_address"),
				rs.getString("venue_city"), rs.getString("venue_country"));
	}

	private static final String SESSION_SELECT = "SELECT s.id, s.event_id, s.name, s.starts_at, s.ends_at, s.venue_id, v.name AS venue_name, v.address AS venue_address, v.city AS venue_city, v.country AS venue_country FROM event_sessions s LEFT JOIN venues v ON v.id = s.venue_id";

	List<SessionRow> findSessions(String eventId) {
		return jdbc.sql(SESSION_SELECT + " WHERE s.event_id = :event ORDER BY s.starts_at, s.id").param("event", eventId)
				.query(EventRepository::mapSession).list();
	}

	Optional<SessionRow> findSession(String eventId, String sessionId) {
		return jdbc.sql(SESSION_SELECT + " WHERE s.id = :id AND s.event_id = :event").param("id", sessionId)
				.param("event", eventId).query(EventRepository::mapSession).optional();
	}

	String insertSession(String eventId, String name, LocalDateTime startsAt, LocalDateTime endsAt, String venueId) {
		String id = UUID.randomUUID().toString();
		jdbc.sql("INSERT INTO event_sessions (id, event_id, name, starts_at, ends_at, venue_id, created_at) VALUES (:id, :event, :name, :starts, :ends, :venue, :now)")
				.param("id", id).param("event", eventId).param("name", name).param("starts", startsAt)
				.param("ends", endsAt).param("venue", venueId).param("now", now()).update();
		return id;
	}

	void updateSession(String sessionId, String name, LocalDateTime startsAt, LocalDateTime endsAt, String venueId) {
		jdbc.sql("UPDATE event_sessions SET name = :name, starts_at = :starts, ends_at = :ends, venue_id = :venue WHERE id = :id")
				.param("name", name).param("starts", startsAt).param("ends", endsAt).param("venue", venueId)
				.param("id", sessionId).update();
	}

	void deleteSession(String sessionId) {
		jdbc.sql("DELETE FROM event_sessions WHERE id = :id").param("id", sessionId).update();
	}
}
