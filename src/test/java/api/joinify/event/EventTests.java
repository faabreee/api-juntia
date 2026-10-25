package api.joinify.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

import com.jayway.jsonpath.JsonPath;

import api.joinify.IntegrationTestSupport;

@TestPropertySource(properties = "app.storage.dir=build/test-uploads")
class EventTests extends IntegrationTestSupport {

	private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0 };

	private String createOrg(TestUser owner) throws Exception {
		String body = mvc.perform(as(owner, post("/api/organizations")).contentType("application/json")
				.content("{\"name\":\"Productora %s\",\"type\":\"COMPANY\",\"country\":\"PE\",\"currency\":\"PEN\",\"timezone\":\"America/Lima\"}"
						.formatted(Math.random())))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.id");
	}

	private void join(String orgId, TestUser owner, TestUser invitee, String role) throws Exception {
		mvc.perform(as(owner, post("/api/organizations/" + orgId + "/invitations")).contentType("application/json")
				.content("{\"email\":\"%s\",\"role\":\"%s\"}".formatted(invitee.email(), role))).andExpect(status().isCreated());
		mvc.perform(as(invitee, post("/api/invitations/accept")).contentType("application/json")
				.content("{\"token\":\"%s\"}".formatted(lastTokenSentTo(invitee.email())))).andExpect(status().isOk());
	}

	private String createVenue(TestUser user, String orgId, String name) throws Exception {
		String body = mvc.perform(as(user, post("/api/organizations/" + orgId + "/venues")).contentType("application/json")
				.content("{\"name\":\"%s\",\"address\":\"Av. Principal 123\",\"city\":\"Lima\",\"country\":\"PE\",\"capacity\":5000}".formatted(name)))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.id");
	}

	private String createEvent(TestUser user, String orgId, String title) throws Exception {
		String body = mvc.perform(as(user, post("/api/organizations/" + orgId + "/events")).contentType("application/json")
				.content("{\"title\":\"%s\",\"description\":\"Gran evento\",\"timezone\":\"America/Lima\"}".formatted(title)))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT")).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.id");
	}

	private String sessionJson(String name, Instant start, Duration length, String venueId) {
		return "{\"name\":\"%s\",\"startsAt\":\"%s\",\"endsAt\":\"%s\",\"venueId\":%s}".formatted(name, start,
				start.plus(length), venueId == null ? "null" : "\"" + venueId + "\"");
	}

	private void addSession(TestUser user, String orgId, String eventId, String name, Instant start, String venueId) throws Exception {
		mvc.perform(as(user, post("/api/organizations/" + orgId + "/events/" + eventId + "/sessions")).contentType("application/json")
				.content(sessionJson(name, start, Duration.ofHours(8), venueId))).andExpect(status().isCreated());
	}

	private String base(String orgId, String eventId) {
		return "/api/organizations/" + orgId + "/events/" + eventId;
	}

	private String slugOf(TestUser user, String orgId, String eventId) throws Exception {
		return JsonPath.read(mvc.perform(as(user, get(base(orgId, eventId)))).andReturn().getResponse().getContentAsString(), "$.slug");
	}

	/** Criterio de terminado de la Etapa 2: un festival de 3 días con enlace público. */
	@Test
	void threeDayFestival_canBePublishedAndShared() throws Exception {
		TestUser owner = newUser();
		String orgId = createOrg(owner);
		String venueId = createVenue(owner, orgId, "Costa Verde Arena");
		String eventId = createEvent(owner, orgId, "Festival Ñandú 2027");
		String slug = slugOf(owner, orgId, eventId);
		assertThat(slug).startsWith("festival-nandu-2027");

		Instant day1 = Instant.now().plus(Duration.ofDays(60));
		for (int i = 0; i < 3; i++) {
			addSession(owner, orgId, eventId, "Día " + (i + 1), day1.plus(Duration.ofDays(i)), venueId);
		}

		// borrador: no existe para el público
		mvc.perform(get("/api/public/events/" + slug)).andExpect(status().isNotFound());

		mvc.perform(as(owner, post(base(orgId, eventId) + "/publish"))).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("PUBLISHED")).andExpect(jsonPath("$.sessions.length()").value(3));

		// sin sesión (público, anónimo)
		mvc.perform(get("/api/public/events/" + slug)).andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("Festival Ñandú 2027"))
				.andExpect(jsonPath("$.status").value("PUBLISHED"))
				.andExpect(jsonPath("$.organizationName").exists())
				.andExpect(jsonPath("$.sessions.length()").value(3))
				.andExpect(jsonPath("$.sessions[0].name").value("Día 1"))
				.andExpect(jsonPath("$.sessions[0].venue.name").value("Costa Verde Arena"))
				.andExpect(jsonPath("$.sessions[0].venue.city").value("Lima"));
	}

	@Test
	void publish_requiresFutureSessions() throws Exception {
		TestUser owner = newUser();
		String orgId = createOrg(owner);
		String eventId = createEvent(owner, orgId, "Sin sesiones");

		mvc.perform(as(owner, post(base(orgId, eventId) + "/publish"))).andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Agrega al menos una sesión")));

		addSession(owner, orgId, eventId, "Pasada", Instant.now().minus(Duration.ofDays(10)), null);
		mvc.perform(as(owner, post(base(orgId, eventId) + "/publish"))).andExpect(status().isUnprocessableContent());

		addSession(owner, orgId, eventId, "Futura", Instant.now().plus(Duration.ofDays(10)), null);
		mvc.perform(as(owner, post(base(orgId, eventId) + "/publish"))).andExpect(status().isOk());
	}

	@Test
	void stateTransitions_areEnforced() throws Exception {
		TestUser owner = newUser();
		String orgId = createOrg(owner);
		String eventId = createEvent(owner, orgId, "Ciclo de estados");
		String slug = slugOf(owner, orgId, eventId);
		addSession(owner, orgId, eventId, "Única", Instant.now().plus(Duration.ofDays(5)), null);

		mvc.perform(as(owner, post(base(orgId, eventId) + "/unpublish"))).andExpect(status().isConflict()); // es borrador
		mvc.perform(as(owner, post(base(orgId, eventId) + "/publish"))).andExpect(status().isOk());
		mvc.perform(as(owner, post(base(orgId, eventId) + "/publish"))).andExpect(status().isConflict());
		mvc.perform(as(owner, delete(base(orgId, eventId)))).andExpect(status().isConflict()); // publicado no se elimina

		mvc.perform(as(owner, post(base(orgId, eventId) + "/unpublish"))).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("DRAFT"));
		mvc.perform(get("/api/public/events/" + slug)).andExpect(status().isNotFound());

		mvc.perform(as(owner, post(base(orgId, eventId) + "/publish"))).andExpect(status().isOk());
		mvc.perform(as(owner, post(base(orgId, eventId) + "/cancel")).contentType("application/json")
				.content("{\"reason\":\"Lluvia\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

		// cancelado: visible como cancelado, y congelado
		mvc.perform(get("/api/public/events/" + slug)).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CANCELLED")).andExpect(jsonPath("$.cancelReason").value("Lluvia"));
		mvc.perform(as(owner, put(base(orgId, eventId)).contentType("application/json")
				.content("{\"title\":\"Nuevo título\",\"timezone\":\"America/Lima\"}"))).andExpect(status().isConflict());
		mvc.perform(as(owner, post(base(orgId, eventId) + "/sessions")).contentType("application/json")
				.content(sessionJson("Extra", Instant.now().plus(Duration.ofDays(9)), Duration.ofHours(2), null))).andExpect(status().isConflict());
		mvc.perform(as(owner, post(base(orgId, eventId) + "/publish"))).andExpect(status().isConflict());
		mvc.perform(as(owner, post(base(orgId, eventId) + "/cancel"))).andExpect(status().isConflict());
	}

	@Test
	void draftEvent_canBeEditedAndDeleted() throws Exception {
		TestUser owner = newUser();
		String orgId = createOrg(owner);
		String eventId = createEvent(owner, orgId, "Borrador");

		mvc.perform(as(owner, put(base(orgId, eventId)).contentType("application/json")
				.content("{\"title\":\"Borrador editado\",\"description\":\"Otra descripción\",\"timezone\":\"America/Bogota\"}")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Borrador editado"))
				.andExpect(jsonPath("$.timezone").value("America/Bogota"));
		mvc.perform(as(owner, put(base(orgId, eventId)).contentType("application/json")
				.content("{\"title\":\"X Y Z\",\"timezone\":\"Marte/Base\"}"))).andExpect(status().isBadRequest());

		mvc.perform(as(owner, delete(base(orgId, eventId)))).andExpect(status().isNoContent());
		mvc.perform(as(owner, get(base(orgId, eventId)))).andExpect(status().isNotFound());
	}

	@Test
	void sessions_areValidated_andPublishedEventKeepsOne() throws Exception {
		TestUser owner = newUser();
		TestUser other = newUser();
		String orgId = createOrg(owner);
		String otherOrgId = createOrg(other);
		String foreignVenue = createVenue(other, otherOrgId, "Lugar ajeno");
		String eventId = createEvent(owner, orgId, "Validaciones");
		Instant start = Instant.now().plus(Duration.ofDays(3));

		// termina antes de empezar
		mvc.perform(as(owner, post(base(orgId, eventId) + "/sessions")).contentType("application/json")
				.content("{\"name\":\"Mal\",\"startsAt\":\"%s\",\"endsAt\":\"%s\"}".formatted(start, start.minusSeconds(60))))
				.andExpect(status().isBadRequest());
		// lugar de otra organización
		mvc.perform(as(owner, post(base(orgId, eventId) + "/sessions")).contentType("application/json")
				.content(sessionJson("Mal", start, Duration.ofHours(2), foreignVenue))).andExpect(status().isNotFound());

		String body = mvc.perform(as(owner, post(base(orgId, eventId) + "/sessions")).contentType("application/json")
				.content(sessionJson("Buena", start, Duration.ofHours(2), null))).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String sessionId = JsonPath.read(body, "$.id");

		mvc.perform(as(owner, put(base(orgId, eventId) + "/sessions/" + sessionId)).contentType("application/json")
				.content(sessionJson("Renombrada", start, Duration.ofHours(3), null))).andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Renombrada"));

		mvc.perform(as(owner, post(base(orgId, eventId) + "/publish"))).andExpect(status().isOk());
		mvc.perform(as(owner, delete(base(orgId, eventId) + "/sessions/" + sessionId))).andExpect(status().isConflict());
	}

	@Test
	void venues_crud_andInUseProtection() throws Exception {
		TestUser owner = newUser();
		String orgId = createOrg(owner);
		String used = createVenue(owner, orgId, "Estadio");
		String unused = createVenue(owner, orgId, "Teatro");
		String eventId = createEvent(owner, orgId, "Con lugar");
		addSession(owner, orgId, eventId, "Noche", Instant.now().plus(Duration.ofDays(4)), used);

		mvc.perform(as(owner, get("/api/organizations/" + orgId + "/venues"))).andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2));
		mvc.perform(as(owner, put("/api/organizations/" + orgId + "/venues/" + unused)).contentType("application/json")
				.content("{\"name\":\"Teatro Municipal\",\"country\":\"PE\"}")).andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Teatro Municipal"));
		mvc.perform(as(owner, post("/api/organizations/" + orgId + "/venues")).contentType("application/json")
				.content("{\"name\":\"Sin país\",\"country\":\"ZZ\"}")).andExpect(status().isBadRequest());

		mvc.perform(as(owner, delete("/api/organizations/" + orgId + "/venues/" + used))).andExpect(status().isConflict());
		mvc.perform(as(owner, delete("/api/organizations/" + orgId + "/venues/" + unused))).andExpect(status().isNoContent());
	}

	@Test
	void cover_uploadReplaceRemove_andPublicAccess() throws Exception {
		TestUser owner = newUser();
		String orgId = createOrg(owner);
		String eventId = createEvent(owner, orgId, "Con portada");
		String slug = slugOf(owner, orgId, eventId);
		addSession(owner, orgId, eventId, "Día", Instant.now().plus(Duration.ofDays(4)), null);

		// no es imagen (ni por extensión ni por contenido)
		mvc.perform(as(owner, multipart(base(orgId, eventId) + "/cover").file(new MockMultipartFile("file", "x.png", "image/png", "no soy imagen".getBytes()))))
				.andExpect(status().isBadRequest());

		mvc.perform(as(owner, multipart(base(orgId, eventId) + "/cover").file(new MockMultipartFile("file", "c.png", "image/png", PNG))))
				.andExpect(status().isOk()).andExpect(jsonPath("$.hasCover").value(true));
		mvc.perform(as(owner, get(base(orgId, eventId) + "/cover"))).andExpect(status().isOk())
				.andExpect(content().contentType("image/png")).andExpect(content().bytes(PNG));

		// borrador: la portada tampoco es pública
		mvc.perform(get("/api/public/events/" + slug + "/cover")).andExpect(status().isNotFound());
		mvc.perform(as(owner, post(base(orgId, eventId) + "/publish"))).andExpect(status().isOk());
		mvc.perform(get("/api/public/events/" + slug + "/cover")).andExpect(status().isOk()).andExpect(content().contentType("image/png"));
		mvc.perform(get("/api/public/events/" + slug)).andExpect(jsonPath("$.coverUrl").value("/api/public/events/" + slug + "/cover"));

		mvc.perform(as(owner, delete(base(orgId, eventId) + "/cover"))).andExpect(status().isNoContent());
		mvc.perform(get("/api/public/events/" + slug + "/cover")).andExpect(status().isNotFound());
	}

	@Test
	void list_filtersByStatusTextAndDates_withPagination() throws Exception {
		TestUser owner = newUser();
		String orgId = createOrg(owner);
		String a = createEvent(owner, orgId, "Concierto Rock");
		String b = createEvent(owner, orgId, "Concierto Jazz");
		createEvent(owner, orgId, "Obra de teatro"); // sin sesiones
		addSession(owner, orgId, a, "Noche", Instant.now().plus(Duration.ofDays(10)), null);
		addSession(owner, orgId, b, "Tarde", Instant.now().plus(Duration.ofDays(100)), null);
		mvc.perform(as(owner, post(base(orgId, b) + "/publish"))).andExpect(status().isOk());
		String list = "/api/organizations/" + orgId + "/events";

		mvc.perform(as(owner, get(list))).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3));
		mvc.perform(as(owner, get(list).param("status", "PUBLISHED"))).andExpect(jsonPath("$.total").value(1))
				.andExpect(jsonPath("$.items[0].title").value("Concierto Jazz")).andExpect(jsonPath("$.items[0].sessionCount").value(1));
		mvc.perform(as(owner, get(list).param("q", "concierto"))).andExpect(jsonPath("$.total").value(2));
		mvc.perform(as(owner, get(list).param("q", "100%"))).andExpect(jsonPath("$.total").value(0));
		mvc.perform(as(owner, get(list).param("from", Instant.now().plus(Duration.ofDays(50)).toString())))
				.andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].title").value("Concierto Jazz"));
		mvc.perform(as(owner, get(list).param("to", Instant.now().plus(Duration.ofDays(50)).toString())))
				.andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].title").value("Concierto Rock"));
		mvc.perform(as(owner, get(list).param("size", "2"))).andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.total").value(3));
		mvc.perform(as(owner, get(list).param("size", "2").param("page", "1"))).andExpect(jsonPath("$.items.length()").value(1));
	}

	@Test
	void slugs_areUnique() throws Exception {
		TestUser owner = newUser();
		String orgId = createOrg(owner);
		String first = slugOf(owner, orgId, createEvent(owner, orgId, "Mismo Nombre"));
		String second = slugOf(owner, orgId, createEvent(owner, orgId, "Mismo Nombre"));
		assertThat(first).isNotEqualTo(second);
		assertThat(second).startsWith("mismo-nombre-");
	}

	@Test
	void roles_membersReadButCannotManage() throws Exception {
		TestUser owner = newUser();
		TestUser member = newUser();
		TestUser admin = newUser();
		String orgId = createOrg(owner);
		join(orgId, owner, member, "MEMBER");
		join(orgId, owner, admin, "ADMIN");
		String eventId = createEvent(owner, orgId, "Visible");
		String eventJson = "{\"title\":\"Nuevo evento\",\"timezone\":\"America/Lima\"}";

		mvc.perform(as(member, get("/api/organizations/" + orgId + "/events"))).andExpect(status().isOk());
		mvc.perform(as(member, get(base(orgId, eventId)))).andExpect(status().isOk());
		mvc.perform(as(member, post("/api/organizations/" + orgId + "/events")).contentType("application/json").content(eventJson))
				.andExpect(status().isForbidden());
		mvc.perform(as(member, post(base(orgId, eventId) + "/publish"))).andExpect(status().isForbidden());
		mvc.perform(as(member, post("/api/organizations/" + orgId + "/venues")).contentType("application/json")
				.content("{\"name\":\"Lugar\",\"country\":\"PE\"}")).andExpect(status().isForbidden());

		mvc.perform(as(admin, post("/api/organizations/" + orgId + "/events")).contentType("application/json").content(eventJson))
				.andExpect(status().isCreated());
	}

	@Test
	void organizations_areIsolated_forEventsAndVenues() throws Exception {
		TestUser alice = newUser();
		TestUser bob = newUser();
		String aliceOrg = createOrg(alice);
		createOrg(bob);
		String venueId = createVenue(alice, aliceOrg, "Lugar de Alice");
		String eventId = createEvent(alice, aliceOrg, "Evento de Alice");
		addSession(alice, aliceOrg, eventId, "Día", Instant.now().plus(Duration.ofDays(3)), null);
		String sessionId = JsonPath.read(mvc.perform(as(alice, get(base(aliceOrg, eventId)))).andReturn().getResponse().getContentAsString(), "$.sessions[0].id");
		String eventJson = "{\"title\":\"Intruso\",\"timezone\":\"America/Lima\"}";

		mvc.perform(as(bob, get("/api/organizations/" + aliceOrg + "/events"))).andExpect(status().isNotFound());
		mvc.perform(as(bob, get(base(aliceOrg, eventId)))).andExpect(status().isNotFound());
		mvc.perform(as(bob, post("/api/organizations/" + aliceOrg + "/events")).contentType("application/json").content(eventJson)).andExpect(status().isNotFound());
		mvc.perform(as(bob, put(base(aliceOrg, eventId)).contentType("application/json").content(eventJson))).andExpect(status().isNotFound());
		mvc.perform(as(bob, post(base(aliceOrg, eventId) + "/publish"))).andExpect(status().isNotFound());
		mvc.perform(as(bob, post(base(aliceOrg, eventId) + "/cancel"))).andExpect(status().isNotFound());
		mvc.perform(as(bob, delete(base(aliceOrg, eventId)))).andExpect(status().isNotFound());
		mvc.perform(as(bob, delete(base(aliceOrg, eventId) + "/sessions/" + sessionId))).andExpect(status().isNotFound());
		mvc.perform(as(bob, get(base(aliceOrg, eventId) + "/cover"))).andExpect(status().isNotFound());
		mvc.perform(as(bob, get("/api/organizations/" + aliceOrg + "/venues"))).andExpect(status().isNotFound());
		mvc.perform(as(bob, delete("/api/organizations/" + aliceOrg + "/venues/" + venueId))).andExpect(status().isNotFound());

		// usar el id de un evento ajeno a través de la organización propia tampoco funciona
		String bobOrg = JsonPath.read(mvc.perform(as(bob, get("/api/organizations"))).andReturn().getResponse().getContentAsString(), "$[0].id");
		mvc.perform(as(bob, get(base(bobOrg, eventId)))).andExpect(status().isNotFound());
		mvc.perform(as(bob, post(base(bobOrg, eventId) + "/publish"))).andExpect(status().isNotFound());

		mvc.perform(as(alice, get(base(aliceOrg, eventId)))).andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Evento de Alice"));
	}

	@Test
	void unauthenticated_cannotManageEvents_butPublicIsOpen() throws Exception {
		mvc.perform(get("/api/organizations/x/events")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/public/events/no-existe")).andExpect(status().isNotFound());
	}
}
