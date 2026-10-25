package api.joinify.event;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.jayway.jsonpath.JsonPath;

import api.joinify.IntegrationTestSupport;

class TicketTypeTests extends IntegrationTestSupport {

	private record Festival(String orgId, String eventId, List<String> sessionIds, String slug) {
		String types() {
			return "/api/organizations/" + orgId + "/events/" + eventId + "/ticket-types";
		}
	}

	/** Organización con un evento de 3 sesiones (un día de diferencia). */
	private Festival festival(TestUser owner) throws Exception {
		String org = JsonPath.read(mvc.perform(as(owner, post("/api/organizations")).contentType("application/json")
				.content("{\"name\":\"Productora %s\",\"type\":\"COMPANY\",\"country\":\"PE\",\"currency\":\"PEN\",\"timezone\":\"America/Lima\"}"
						.formatted(Math.random()))).andReturn().getResponse().getContentAsString(), "$.id");
		String event = JsonPath.read(mvc.perform(as(owner, post("/api/organizations/" + org + "/events")).contentType("application/json")
				.content("{\"title\":\"Festival de entradas\",\"timezone\":\"America/Lima\"}")).andReturn().getResponse().getContentAsString(), "$.id");
		Instant day1 = Instant.now().plus(Duration.ofDays(30));
		java.util.ArrayList<String> sessions = new java.util.ArrayList<>();
		for (int i = 0; i < 3; i++) {
			Instant start = day1.plus(Duration.ofDays(i));
			sessions.add(JsonPath.read(mvc.perform(as(owner, post("/api/organizations/" + org + "/events/" + event + "/sessions")).contentType("application/json")
					.content("{\"name\":\"Día %d\",\"startsAt\":\"%s\",\"endsAt\":\"%s\"}".formatted(i + 1, start, start.plus(Duration.ofHours(8)))))
					.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id"));
		}
		String slug = JsonPath.read(mvc.perform(as(owner, get("/api/organizations/" + org + "/events/" + event))).andReturn().getResponse().getContentAsString(), "$.slug");
		return new Festival(org, event, sessions, slug);
	}

	private String json(String name, String template, long price, String scope, List<String> sessions, Integer total, Integer perSession) {
		return "{\"name\":\"%s\",\"template\":\"%s\",\"priceMinor\":%d,\"scope\":\"%s\",\"sessionIds\":%s,\"maxTotalUses\":%s,\"maxUsesPerSession\":%s}"
				.formatted(name, template, price, scope,
						sessions.stream().map(s -> "\"" + s + "\"").collect(java.util.stream.Collectors.joining(",", "[", "]")),
						total, perSession);
	}

	private String createType(TestUser user, Festival f, String body) throws Exception {
		return JsonPath.read(mvc.perform(as(user, post(f.types())).contentType("application/json").content(body))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
	}

	private void join(String orgId, TestUser owner, TestUser invitee, String role) throws Exception {
		mvc.perform(as(owner, post("/api/organizations/" + orgId + "/invitations")).contentType("application/json")
				.content("{\"email\":\"%s\",\"role\":\"%s\"}".formatted(invitee.email(), role))).andExpect(status().isCreated());
		mvc.perform(as(invitee, post("/api/invitations/accept")).contentType("application/json")
				.content("{\"token\":\"%s\"}".formatted(lastTokenSentTo(invitee.email())))).andExpect(status().isOk());
	}

	/** Criterio de terminado de la Etapa 3: todos los ejemplos del ADR 0004 se configuran y se conservan. */
	@Test
	void adrExamples_canBeConfigured() throws Exception {
		TestUser owner = newUser();
		Festival f = festival(owner);
		List<String> all = f.sessionIds();

		createType(owner, f, json("Entrada única Día 1", "SINGLE", 8000, "SELECTED_SESSIONS", List.of(all.get(0)), 1, 1));
		createType(owner, f, json("Pase 3 días", "MULTI_DAY", 20000, "SELECTED_SESSIONS", all, null, 1));
		createType(owner, f, json("Pase de 3 usos", "N_USES", 15000, "ALL_SESSIONS", List.of(), 3, 1));
		createType(owner, f, json("VIP reingreso libre", "CUSTOM", 50000, "ALL_SESSIONS", List.of(), null, null));
		createType(owner, f, json("Invitación (gratis)", "SINGLE", 0, "SELECTED_SESSIONS", List.of(all.get(1)), 1, 1));

		mvc.perform(as(owner, get(f.types()))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(5))
				// 1 Entrada única
				.andExpect(jsonPath("$[0].template").value("SINGLE")).andExpect(jsonPath("$[0].scope").value("SELECTED_SESSIONS"))
				.andExpect(jsonPath("$[0].sessionIds.length()").value(1)).andExpect(jsonPath("$[0].maxTotalUses").value(1))
				.andExpect(jsonPath("$[0].maxUsesPerSession").value(1)).andExpect(jsonPath("$[0].currency").value("PEN"))
				// 2 Pase por días
				.andExpect(jsonPath("$[1].sessionIds.length()").value(3)).andExpect(jsonPath("$[1].maxTotalUses").doesNotExist())
				.andExpect(jsonPath("$[1].maxUsesPerSession").value(1))
				// 3 Pase de N usos
				.andExpect(jsonPath("$[2].scope").value("ALL_SESSIONS")).andExpect(jsonPath("$[2].sessionIds.length()").value(0))
				.andExpect(jsonPath("$[2].maxTotalUses").value(3))
				// 4 reingreso libre
				.andExpect(jsonPath("$[3].maxUsesPerSession").doesNotExist()).andExpect(jsonPath("$[3].earlyEntryMinutes").value(60))
				// gratis
				.andExpect(jsonPath("$[4].priceMinor").value(0));
	}

	@Test
	void invalidRules_areRejectedWithTheReason() throws Exception {
		TestUser owner = newUser();
		Festival f = festival(owner);
		TestUser other = newUser();
		Festival foreign = festival(other);

		// SELECTED sin sesiones
		mvc.perform(as(owner, post(f.types())).contentType("application/json")
				.content(json("Mal", "CUSTOM", 100, "SELECTED_SESSIONS", List.of(), 1, 1)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(containsString("al menos una sesión")));
		// sesión de otro evento
		mvc.perform(as(owner, post(f.types())).contentType("application/json")
				.content(json("Mal", "CUSTOM", 100, "SELECTED_SESSIONS", List.of(foreign.sessionIds().get(0)), 1, 1)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(containsString("no pertenecen")));
		// por sesión mayor que total
		mvc.perform(as(owner, post(f.types())).contentType("application/json")
				.content(json("Mal", "CUSTOM", 100, "ALL_SESSIONS", List.of(), 2, 3)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(containsString("no pueden superar")));
		// cero usos
		mvc.perform(as(owner, post(f.types())).contentType("application/json")
				.content(json("Mal", "CUSTOM", 100, "ALL_SESSIONS", List.of(), 0, 1))).andExpect(status().isBadRequest());
		// precio negativo, sin nombre, plantilla desconocida
		mvc.perform(as(owner, post(f.types())).contentType("application/json")
				.content(json("Mal", "CUSTOM", -1, "ALL_SESSIONS", List.of(), null, null))).andExpect(status().isBadRequest());
		mvc.perform(as(owner, post(f.types())).contentType("application/json")
				.content(json("", "CUSTOM", 100, "ALL_SESSIONS", List.of(), null, null))).andExpect(status().isBadRequest());
		mvc.perform(as(owner, post(f.types())).contentType("application/json")
				.content(json("Mal", "OTRA", 100, "ALL_SESSIONS", List.of(), null, null))).andExpect(status().isBadRequest());
		// periodo de venta invertido y máximo por orden mayor que el cupo
		Instant now = Instant.now();
		mvc.perform(as(owner, post(f.types())).contentType("application/json")
				.content("{\"name\":\"Mal\",\"template\":\"CUSTOM\",\"priceMinor\":100,\"scope\":\"ALL_SESSIONS\",\"salesStart\":\"%s\",\"salesEnd\":\"%s\"}"
						.formatted(now.plus(Duration.ofDays(2)), now.plus(Duration.ofDays(1)))))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(containsString("periodo de venta")));
		mvc.perform(as(owner, post(f.types())).contentType("application/json")
				.content("{\"name\":\"Mal\",\"template\":\"CUSTOM\",\"priceMinor\":100,\"scope\":\"ALL_SESSIONS\",\"quota\":5,\"maxPerOrder\":6}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(containsString("cupo")));

		mvc.perform(as(owner, get(f.types()))).andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void update_andDelete() throws Exception {
		TestUser owner = newUser();
		Festival f = festival(owner);
		String id = createType(owner, f, json("General", "N_USES", 5000, "ALL_SESSIONS", List.of(), 2, 1));

		mvc.perform(as(owner, put(f.types() + "/" + id)).contentType("application/json")
				.content("{\"name\":\"General plus\",\"template\":\"CUSTOM\",\"priceMinor\":7000,\"quota\":100,\"maxPerOrder\":4,\"scope\":\"SELECTED_SESSIONS\",\"sessionIds\":[\"%s\"],\"maxTotalUses\":1,\"maxUsesPerSession\":1,\"earlyEntryMinutes\":30,\"active\":false}"
						.formatted(f.sessionIds().get(2))))
				.andExpect(status().isOk()).andExpect(jsonPath("$.name").value("General plus")).andExpect(jsonPath("$.priceMinor").value(7000))
				.andExpect(jsonPath("$.quota").value(100)).andExpect(jsonPath("$.maxPerOrder").value(4))
				.andExpect(jsonPath("$.earlyEntryMinutes").value(30)).andExpect(jsonPath("$.active").value(false))
				.andExpect(jsonPath("$.sessionIds[0]").value(f.sessionIds().get(2)));

		mvc.perform(as(owner, delete(f.types() + "/" + id))).andExpect(status().isNoContent());
		mvc.perform(as(owner, delete(f.types() + "/" + id))).andExpect(status().isNotFound());
	}

	@Test
	void sessionUsedByATicketType_cannotBeDeleted() throws Exception {
		TestUser owner = newUser();
		Festival f = festival(owner);
		String sessionId = f.sessionIds().get(0);
		String typeId = createType(owner, f, json("Solo día 1", "SINGLE", 100, "SELECTED_SESSIONS", List.of(sessionId), 1, 1));
		String sessionUrl = "/api/organizations/" + f.orgId() + "/events/" + f.eventId() + "/sessions/" + sessionId;

		mvc.perform(as(owner, delete(sessionUrl))).andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value(containsString("tipo de entrada")));

		mvc.perform(as(owner, put(f.types() + "/" + typeId)).contentType("application/json")
				.content(json("Todas", "N_USES", 100, "ALL_SESSIONS", List.of(), 1, 1))).andExpect(status().isOk());
		mvc.perform(as(owner, delete(sessionUrl))).andExpect(status().isNoContent());
	}

	@Test
	void publicPage_showsOnlyActiveTicketTypes_withSaleStatus() throws Exception {
		TestUser owner = newUser();
		Festival f = festival(owner);
		Instant now = Instant.now();
		createType(owner, f, "{\"name\":\"En venta\",\"template\":\"CUSTOM\",\"priceMinor\":1000,\"scope\":\"ALL_SESSIONS\"}");
		createType(owner, f, "{\"name\":\"Pronto\",\"template\":\"CUSTOM\",\"priceMinor\":1000,\"scope\":\"ALL_SESSIONS\",\"salesStart\":\"%s\"}".formatted(now.plus(Duration.ofDays(5))));
		createType(owner, f, "{\"name\":\"Terminó\",\"template\":\"CUSTOM\",\"priceMinor\":1000,\"scope\":\"ALL_SESSIONS\",\"salesStart\":\"%s\",\"salesEnd\":\"%s\"}".formatted(now.minus(Duration.ofDays(5)), now.minus(Duration.ofDays(1))));
		createType(owner, f, "{\"name\":\"Oculta\",\"template\":\"CUSTOM\",\"priceMinor\":1000,\"scope\":\"ALL_SESSIONS\",\"active\":false}");

		mvc.perform(as(owner, post("/api/organizations/" + f.orgId() + "/events/" + f.eventId() + "/publish"))).andExpect(status().isOk());

		mvc.perform(get("/api/public/events/" + f.slug())).andExpect(status().isOk())
				.andExpect(jsonPath("$.ticketTypes.length()").value(3))
				.andExpect(jsonPath("$.ticketTypes[?(@.name=='En venta')].saleStatus").value("ON_SALE"))
				.andExpect(jsonPath("$.ticketTypes[?(@.name=='Pronto')].saleStatus").value("NOT_STARTED"))
				.andExpect(jsonPath("$.ticketTypes[?(@.name=='Terminó')].saleStatus").value("ENDED"))
				.andExpect(jsonPath("$.ticketTypes[?(@.name=='Oculta')]").isEmpty());

		// cancelado: ya no vende
		mvc.perform(as(owner, post("/api/organizations/" + f.orgId() + "/events/" + f.eventId() + "/cancel"))).andExpect(status().isOk());
		mvc.perform(get("/api/public/events/" + f.slug())).andExpect(jsonPath("$.ticketTypes.length()").value(0));
		mvc.perform(as(owner, post(f.types())).contentType("application/json")
				.content(json("Tarde", "CUSTOM", 100, "ALL_SESSIONS", List.of(), null, null))).andExpect(status().isConflict());
	}

	@Test
	void roles_andIsolation() throws Exception {
		TestUser owner = newUser();
		TestUser member = newUser();
		TestUser outsider = newUser();
		Festival f = festival(owner);
		join(f.orgId(), owner, member, "MEMBER");
		String id = createType(owner, f, json("General", "CUSTOM", 100, "ALL_SESSIONS", List.of(), null, null));
		String body = json("Nueva", "CUSTOM", 100, "ALL_SESSIONS", List.of(), null, null);

		// MEMBER lee pero no gestiona
		mvc.perform(as(member, get(f.types()))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
		mvc.perform(as(member, post(f.types())).contentType("application/json").content(body)).andExpect(status().isForbidden());
		mvc.perform(as(member, put(f.types() + "/" + id)).contentType("application/json").content(body)).andExpect(status().isForbidden());
		mvc.perform(as(member, delete(f.types() + "/" + id))).andExpect(status().isForbidden());

		// otra organización: 404 en todo
		mvc.perform(as(outsider, get(f.types()))).andExpect(status().isNotFound());
		mvc.perform(as(outsider, post(f.types())).contentType("application/json").content(body)).andExpect(status().isNotFound());
		mvc.perform(as(outsider, put(f.types() + "/" + id)).contentType("application/json").content(body)).andExpect(status().isNotFound());
		mvc.perform(as(outsider, delete(f.types() + "/" + id))).andExpect(status().isNotFound());

		// anónimo
		mvc.perform(get(f.types())).andExpect(status().isUnauthorized());
	}
}
