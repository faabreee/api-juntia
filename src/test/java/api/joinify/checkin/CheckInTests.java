package api.joinify.checkin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import api.joinify.IntegrationTestSupport;
import api.joinify.ticket.TicketTokenService;

@TestPropertySource(properties = { "app.storage.dir=build/test-uploads", "app.fees.bps=0", "app.payments.reconcile-interval-ms=3600000",
		"app.tickets.repair-interval-ms=3600000" })
class CheckInTests extends IntegrationTestSupport {

	@Autowired
	JdbcClient jdbc;

	@Autowired
	TicketTokenService tokens;

	private record Gate(TestUser owner, String orgId, String eventId, String slug, String sessionA, String sessionB, String sessionFuture,
			String sessionEnded) {
		String base() {
			return "/api/organizations/" + orgId + "/events/" + eventId;
		}
	}

	private record Ticket(String id, String code, String token, String buyerEmail) {
	}

	/**
	 * Evento publicado con cuatro sesiones: A y B en curso, una futura (dentro de 3 h: muy pronto con 60 min de
	 * anticipación) y una que ya terminó.
	 */
	private Gate gate() throws Exception {
		TestUser owner = newUser();
		String org = JsonPath.read(mvc.perform(as(owner, post("/api/organizations")).contentType("application/json")
				.content("{\"name\":\"Productora %s\",\"type\":\"COMPANY\",\"country\":\"PE\",\"currency\":\"PEN\",\"timezone\":\"America/Lima\"}"
						.formatted(Math.random()))).andReturn().getResponse().getContentAsString(), "$.id");
		String event = JsonPath.read(mvc.perform(as(owner, post("/api/organizations/" + org + "/events")).contentType("application/json")
				.content("{\"title\":\"Festival con puertas\",\"timezone\":\"America/Lima\"}")).andReturn().getResponse().getContentAsString(), "$.id");
		Instant now = Instant.now();
		String a = session(owner, org, event, "Sesión A", now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(5)));
		String b = session(owner, org, event, "Sesión B", now.minus(Duration.ofMinutes(30)), now.plus(Duration.ofHours(6)));
		String future = session(owner, org, event, "Sesión futura", now.plus(Duration.ofHours(3)), now.plus(Duration.ofHours(8)));
		String ended = session(owner, org, event, "Sesión terminada", now.minus(Duration.ofHours(6)), now.minus(Duration.ofHours(1)));
		mvc.perform(as(owner, post("/api/organizations/" + org + "/events/" + event + "/publish"))).andExpect(status().isOk());
		String slug = JsonPath.read(mvc.perform(as(owner, get("/api/organizations/" + org + "/events/" + event))).andReturn().getResponse().getContentAsString(), "$.slug");
		return new Gate(owner, org, event, slug, a, b, future, ended);
	}

	private String session(TestUser owner, String org, String event, String name, Instant start, Instant end) throws Exception {
		return JsonPath.read(mvc.perform(as(owner, post("/api/organizations/" + org + "/events/" + event + "/sessions")).contentType("application/json")
				.content("{\"name\":\"%s\",\"startsAt\":\"%s\",\"endsAt\":\"%s\"}".formatted(name, start, end)))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
	}

	/** Tipo de entrada con la regla indicada (ADR 0004). Sesiones nulas o vacías = todas. */
	private String type(Gate g, String name, List<String> sessions, Integer total, Integer perSession, boolean nominative) throws Exception {
		boolean all = sessions == null || sessions.isEmpty(); // sin lista = todas las sesiones
		String scope = all ? "ALL_SESSIONS" : "SELECTED_SESSIONS";
		String ids = all ? "[]" : sessions.stream().map(s -> "\"" + s + "\"").collect(java.util.stream.Collectors.joining(",", "[", "]"));
		return JsonPath.read(mvc.perform(as(g.owner(), post(g.base() + "/ticket-types")).contentType("application/json")
				.content("{\"name\":\"%s\",\"template\":\"CUSTOM\",\"priceMinor\":0,\"quota\":50,\"maxPerOrder\":10,\"scope\":\"%s\",\"sessionIds\":%s,\"maxTotalUses\":%s,\"maxUsesPerSession\":%s,\"nominative\":%s}"
						.formatted(name, scope, ids, total, perSession, nominative))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
	}

	/** Compra gratuita (se emite al instante) y devuelve los boletos con su QR. */
	private List<Ticket> buy(Gate g, String typeId, int quantity) throws Exception {
		TestUser buyer = newUser();
		mvc.perform(as(buyer, post("/api/orders")).header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
				.content("{\"eventSlug\":\"%s\",\"items\":[{\"ticketTypeId\":\"%s\",\"quantity\":%d}]}".formatted(g.slug(), typeId, quantity)))
				.andExpect(status().isCreated());
		String body = mvc.perform(as(buyer, get("/api/tickets"))).andReturn().getResponse().getContentAsString();
		List<String> ids = JsonPath.read(body, "$[*].id");
		List<String> codes = JsonPath.read(body, "$[*].code");
		List<String> qrs = JsonPath.read(body, "$[*].qrToken");
		List<Ticket> tickets = new ArrayList<>();
		for (int i = 0; i < ids.size(); i++) {
			tickets.add(new Ticket(ids.get(i), codes.get(i), qrs.get(i), buyer.email()));
		}
		return tickets;
	}

	private String device(TestUser user, Gate g, String name) throws Exception {
		return JsonPath.read(mvc.perform(as(user, post("/api/organizations/" + g.orgId() + "/devices")).contentType("application/json")
				.content("{\"name\":\"%s\"}".formatted(name))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
	}

	private ResultActions scanToken(TestUser user, Gate g, String deviceId, String token, String sessionId) throws Exception {
		return scan(user, g, "{\"scanId\":\"%s\",\"deviceId\":\"%s\",%s\"token\":\"%s\"}".formatted(UUID.randomUUID(), deviceId, session(sessionId), token));
	}

	private ResultActions scan(TestUser user, Gate g, String body) throws Exception {
		return mvc.perform(as(user, post(g.base() + "/checkins")).contentType("application/json").content(body));
	}

	private static String session(String sessionId) {
		return sessionId == null ? "" : "\"sessionId\":\"%s\",".formatted(sessionId);
	}

	private void assertAccepted(ResultActions result) throws Exception {
		result.andExpect(status().isOk()).andExpect(jsonPath("$.result").value("ACCEPTED")).andExpect(jsonPath("$.reason").doesNotExist());
	}

	private void assertRejected(ResultActions result, String reason) throws Exception {
		result.andExpect(status().isOk()).andExpect(jsonPath("$.result").value("REJECTED")).andExpect(jsonPath("$.reason").value(reason));
	}

	private long accepted(String ticketId) {
		return jdbc.sql("SELECT COUNT(*) FROM check_ins WHERE ticket_id = :id AND result = 'ACCEPTED' AND reverted_at IS NULL").param("id", ticketId)
				.query(Long.class).single();
	}

	// ---------- reglas del ADR 0004 aplicadas en puerta ----------

	@Test
	void dayPass_entersOncePerSession() throws Exception {
		Gate g = gate();
		TestUser staff = g.owner();
		String dev = device(staff, g, "Puerta A");
		Ticket t = buy(g, type(g, "Pase", List.of(), null, 1, false), 1).getFirst();

		assertAccepted(scanToken(staff, g, dev, t.token(), g.sessionA()));
		assertRejected(scanToken(staff, g, dev, t.token(), g.sessionA()), "ALREADY_USED_IN_SESSION");
		assertAccepted(scanToken(staff, g, dev, t.token(), g.sessionB())); // otra sesión del mismo pase
		assertRejected(scanToken(staff, g, dev, t.token(), g.sessionB()), "ALREADY_USED_IN_SESSION");
		assertThat(accepted(t.id())).isEqualTo(2);
	}

	@Test
	void singleTicket_enters_onlyOnce_andOnlyItsSession() throws Exception {
		Gate g = gate();
		String dev = device(g.owner(), g, "Puerta A");
		Ticket t = buy(g, type(g, "Única A", List.of(g.sessionA()), 1, 1, false), 1).getFirst();

		assertRejected(scanToken(g.owner(), g, dev, t.token(), g.sessionB()), "SESSION_NOT_INCLUDED");
		assertAccepted(scanToken(g.owner(), g, dev, t.token(), g.sessionA()));
		assertRejected(scanToken(g.owner(), g, dev, t.token(), g.sessionA()), "TOTAL_USES_EXHAUSTED");
	}

	@Test
	void freeReentry_alwaysLetsTheTicketBackIn() throws Exception {
		Gate g = gate();
		String dev = device(g.owner(), g, "Puerta A");
		Ticket t = buy(g, type(g, "VIP", null, null, null, false), 1).getFirst();

		for (int i = 0; i < 5; i++) {
			assertAccepted(scanToken(g.owner(), g, dev, t.token(), g.sessionA()));
		}
		assertThat(accepted(t.id())).isEqualTo(5);
	}

	@Test
	void timeWindow_tooEarly_andSessionEnded() throws Exception {
		Gate g = gate();
		String dev = device(g.owner(), g, "Puerta A");
		Ticket t = buy(g, type(g, "Todas", null, null, null, false), 1).getFirst();

		assertRejected(scanToken(g.owner(), g, dev, t.token(), g.sessionFuture()), "TOO_EARLY");
		assertRejected(scanToken(g.owner(), g, dev, t.token(), g.sessionEnded()), "SESSION_ENDED");
		assertThat(accepted(t.id())).isZero();
	}

	@Test
	void withoutASession_theOneInProgressIsChosenAutomatically() throws Exception {
		Gate g = gate();
		String dev = device(g.owner(), g, "Puerta A");
		// cubre la sesión terminada y la B (en curso): debe elegir la B
		Ticket both = buy(g, type(g, "Terminada y B", List.of(g.sessionEnded(), g.sessionB()), null, 1, false), 1).getFirst();
		assertAccepted(scanToken(g.owner(), g, dev, both.token(), null).andExpect(jsonPath("$.session.name").value("Sesión B")));
		// solo la terminada: se explica por qué no entra
		Ticket old = buy(g, type(g, "Solo terminada", List.of(g.sessionEnded()), null, 1, false), 1).getFirst();
		assertRejected(scanToken(g.owner(), g, dev, old.token(), null).andExpect(jsonPath("$.session.name").value("Sesión terminada")), "SESSION_ENDED");
		// solo la futura
		Ticket soon = buy(g, type(g, "Solo futura", List.of(g.sessionFuture()), null, 1, false), 1).getFirst();
		assertRejected(scanToken(g.owner(), g, dev, soon.token(), null).andExpect(jsonPath("$.session.name").value("Sesión futura")), "TOO_EARLY");
	}

	@Test
	void rejectionExplainsWhoLetTheTicketInBefore() throws Exception {
		Gate g = gate();
		String front = device(g.owner(), g, "Puerta principal");
		String side = device(g.owner(), g, "Puerta lateral");
		Ticket t = buy(g, type(g, "Pase", List.of(), null, 1, false), 1).getFirst();

		assertAccepted(scanToken(g.owner(), g, front, t.token(), g.sessionA()));
		scanToken(g.owner(), g, side, t.token(), g.sessionA()).andExpect(jsonPath("$.result").value("REJECTED"))
				.andExpect(jsonPath("$.previousEntry.deviceName").value("Puerta principal")).andExpect(jsonPath("$.previousEntry.at").exists())
				.andExpect(jsonPath("$.entriesInSession").value(1)).andExpect(jsonPath("$.ticket.code").value(t.code()));
	}

	// ---------- criterio de terminado: varias puertas a la vez sin doble ingreso ----------

	@Test
	void twoGatesScanningTheSameTicketAtTheSameTime_onlyOneGetsIn() throws Exception {
		Gate g = gate();
		String d1 = device(g.owner(), g, "Puerta 1");
		String d2 = device(g.owner(), g, "Puerta 2");
		Ticket t = buy(g, type(g, "Pase", List.of(), null, 1, false), 1).getFirst();

		List<String> results = scanConcurrently(g, t.token(), g.sessionA(), 12, d1, d2);

		assertThat(results.stream().filter(r -> r.equals("ACCEPTED")).count()).as("entra una sola vez").isEqualTo(1);
		assertThat(results.stream().filter(r -> r.equals("REJECTED:ALREADY_USED_IN_SESSION")).count()).isEqualTo(11);
		assertThat(accepted(t.id())).isEqualTo(1);
	}

	@Test
	void concurrentScans_neverExceedTheTotalLimit() throws Exception {
		Gate g = gate();
		String d1 = device(g.owner(), g, "Puerta 1");
		String d2 = device(g.owner(), g, "Puerta 2");
		String d3 = device(g.owner(), g, "Puerta 3");
		// 3 ingresos en total, con reingreso libre en la sesión
		Ticket t = buy(g, type(g, "Tres usos", null, 3, null, false), 1).getFirst();

		List<String> results = scanConcurrently(g, t.token(), g.sessionA(), 15, d1, d2, d3);

		assertThat(results.stream().filter(r -> r.equals("ACCEPTED")).count()).isEqualTo(3);
		assertThat(results.stream().filter(r -> r.equals("REJECTED:TOTAL_USES_EXHAUSTED")).count()).isEqualTo(12);
		assertThat(accepted(t.id())).isEqualTo(3);
	}

	@Test
	void manyDifferentTicketsScannedTogether_allEnter() throws Exception {
		Gate g = gate();
		String d1 = device(g.owner(), g, "Puerta 1");
		String d2 = device(g.owner(), g, "Puerta 2");
		List<Ticket> tickets = new ArrayList<>(buy(g, type(g, "Pase", List.of(), null, 1, false), 10));

		ExecutorService pool = Executors.newFixedThreadPool(10);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<String>> futures = new ArrayList<>();
		for (int i = 0; i < tickets.size(); i++) {
			Ticket t = tickets.get(i);
			String dev = i % 2 == 0 ? d1 : d2;
			futures.add(pool.submit(() -> {
				go.await();
				return resultOf(scanToken(g.owner(), g, dev, t.token(), g.sessionA()));
			}));
		}
		go.countDown();
		for (Future<String> f : futures) {
			assertThat(f.get()).isEqualTo("ACCEPTED");
		}
		pool.shutdown();
	}

	private List<String> scanConcurrently(Gate g, String token, String sessionId, int scans, String... devices) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(scans);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<String>> futures = new ArrayList<>();
		for (int i = 0; i < scans; i++) {
			String dev = devices[i % devices.length];
			futures.add(pool.submit(() -> {
				go.await();
				return resultOf(scanToken(g.owner(), g, dev, token, sessionId));
			}));
		}
		go.countDown();
		List<String> results = new ArrayList<>();
		for (Future<String> f : futures) {
			results.add(f.get());
		}
		pool.shutdown();
		return results;
	}

	private String resultOf(ResultActions action) throws Exception {
		String body = action.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		String result = JsonPath.read(body, "$.result");
		String reason = JsonPath.read(body, "$.reason");
		return reason == null ? result : result + ":" + reason;
	}

	@Test
	void sameScanId_isIdempotent() throws Exception {
		Gate g = gate();
		String dev = device(g.owner(), g, "Puerta A");
		Ticket t = buy(g, type(g, "Pase", List.of(), null, 1, false), 1).getFirst();
		String body = "{\"scanId\":\"%s\",\"deviceId\":\"%s\",\"sessionId\":\"%s\",\"token\":\"%s\"}".formatted("lectura-" + UUID.randomUUID(), dev, g.sessionA(), t.token());

		scan(g.owner(), g, body).andExpect(jsonPath("$.result").value("ACCEPTED")).andExpect(jsonPath("$.replayed").value(false));
		// la red falló y la app reintenta la misma lectura: sigue siendo "aceptado", no "ya usado"
		scan(g.owner(), g, body).andExpect(jsonPath("$.result").value("ACCEPTED")).andExpect(jsonPath("$.replayed").value(true));

		assertThat(accepted(t.id())).isEqualTo(1);
		assertThat(jdbc.sql("SELECT COUNT(*) FROM check_ins WHERE ticket_id = :id").param("id", t.id()).query(Long.class).single()).isEqualTo(1);
	}

	// ---------- códigos que no valen ----------

	@Test
	void invalidCodes_areRejectedAndAudited() throws Exception {
		Gate g = gate();
		Gate other = gate();
		String dev = device(g.owner(), g, "Puerta A");
		Ticket mine = buy(g, type(g, "Pase", List.of(), null, 1, false), 1).getFirst();
		Ticket foreign = buy(other, type(other, "Pase", List.of(), null, 1, false), 1).getFirst();
		String tampered = mine.token().substring(0, mine.token().length() - 3) + (mine.token().endsWith("AAA") ? "BBB" : "AAA");

		assertRejected(scanToken(g.owner(), g, dev, "esto no es un boleto", g.sessionA()), "INVALID_TOKEN");
		assertRejected(scanToken(g.owner(), g, dev, tampered, g.sessionA()), "INVALID_TOKEN");
		assertRejected(scanToken(g.owner(), g, dev, foreign.token(), g.sessionA()), "WRONG_EVENT"); // firma válida, pero de otro evento
		assertThat(jdbc.sql("SELECT COUNT(*) FROM check_ins WHERE event_id = :e AND result = 'REJECTED'").param("e", g.eventId()).query(Long.class).single()).isEqualTo(3);
		assertThat(accepted(mine.id())).isZero();
	}

	@Test
	void voidedTicket_andCancelledEvent_areRejected() throws Exception {
		Gate g = gate();
		String dev = device(g.owner(), g, "Puerta A");
		Ticket t = buy(g, type(g, "Pase", List.of(), null, 1, false), 1).getFirst();
		jdbc.sql("UPDATE tickets SET status = 'VOID' WHERE id = :id").param("id", t.id()).update();
		assertRejected(scanToken(g.owner(), g, dev, t.token(), g.sessionA()), "TICKET_VOID");
		jdbc.sql("UPDATE tickets SET status = 'VALID' WHERE id = :id").param("id", t.id()).update();

		mvc.perform(as(g.owner(), post(g.base() + "/cancel"))).andExpect(status().isOk());
		assertRejected(scanToken(g.owner(), g, dev, t.token(), g.sessionA()), "EVENT_NOT_ACTIVE");
	}

	// ---------- dispositivos y permisos ----------

	@Test
	void deviceMustBeRegisteredInTheOrganization_andNotRevoked() throws Exception {
		Gate g = gate();
		Gate other = gate();
		String dev = device(g.owner(), g, "Puerta A");
		String foreignDevice = device(other.owner(), other, "Puerta de otro");
		Ticket t = buy(g, type(g, "Pase", List.of(), null, 1, false), 1).getFirst();

		scanToken(g.owner(), g, UUID.randomUUID().toString(), t.token(), g.sessionA()).andExpect(status().isForbidden());
		scanToken(g.owner(), g, foreignDevice, t.token(), g.sessionA()).andExpect(status().isForbidden());
		assertAccepted(scanToken(g.owner(), g, dev, t.token(), g.sessionA()));

		mvc.perform(as(g.owner(), delete("/api/organizations/" + g.orgId() + "/devices/" + dev))).andExpect(status().isNoContent());
		scanToken(g.owner(), g, dev, t.token(), g.sessionA()).andExpect(status().isForbidden());
		mvc.perform(as(g.owner(), delete("/api/organizations/" + g.orgId() + "/devices/" + dev))).andExpect(status().isConflict());
		mvc.perform(as(g.owner(), get("/api/organizations/" + g.orgId() + "/devices"))).andExpect(jsonPath("$[?(@.id=='%s')].revoked".formatted(dev)).value(true));
	}

	@Test
	void anyMemberCanValidate_butOnlyManagersSeeTheAuditAndRevert() throws Exception {
		Gate g = gate();
		TestUser staff = newUser();
		TestUser outsider = newUser();
		mvc.perform(as(g.owner(), post("/api/organizations/" + g.orgId() + "/invitations")).contentType("application/json")
				.content("{\"email\":\"%s\",\"role\":\"MEMBER\"}".formatted(staff.email()))).andExpect(status().isCreated());
		mvc.perform(as(staff, post("/api/invitations/accept")).contentType("application/json")
				.content("{\"token\":\"%s\"}".formatted(lastTokenSentTo(staff.email())))).andExpect(status().isOk());
		Ticket t = buy(g, type(g, "Pase", List.of(), null, 1, false), 1).getFirst();
		String dev = device(staff, g, "Puerta del staff");

		assertAccepted(scanToken(staff, g, dev, t.token(), g.sessionA()));
		String checkIn = JsonPath.read(mvc.perform(as(g.owner(), get(g.base() + "/checkins"))).andReturn().getResponse().getContentAsString(), "$[0].id");

		mvc.perform(as(staff, get(g.base() + "/checkins"))).andExpect(status().isForbidden());
		mvc.perform(as(staff, get("/api/organizations/" + g.orgId() + "/devices"))).andExpect(status().isForbidden());
		mvc.perform(as(staff, post(g.base() + "/checkins/" + checkIn + "/revert")).contentType("application/json").content("{\"reason\":\"error\"}")).andExpect(status().isForbidden());
		mvc.perform(as(staff, get(g.base() + "/checkins/summary"))).andExpect(status().isOk());

		scanToken(outsider, g, dev, t.token(), g.sessionA()).andExpect(status().isNotFound());
		mvc.perform(as(outsider, post("/api/organizations/" + g.orgId() + "/devices")).contentType("application/json").content("{\"name\":\"Intruso\"}")).andExpect(status().isNotFound());
		mvc.perform(post(g.base() + "/checkins").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
				.contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
		// un evento ajeno a la organización tampoco se valida con el id de otra
		Gate foreign = gate();
		scanToken(g.owner(), new Gate(g.owner(), g.orgId(), foreign.eventId(), foreign.slug(), null, null, null, null), dev, t.token(), null).andExpect(status().isNotFound());
	}

	@Test
	void scanRequestShape_isValidated() throws Exception {
		Gate g = gate();
		String dev = device(g.owner(), g, "Puerta A");
		Ticket t = buy(g, type(g, "Pase", List.of(), null, 1, false), 1).getFirst();
		String id = "forma-" + UUID.randomUUID();

		scan(g.owner(), g, "{\"scanId\":\"%s\",\"deviceId\":\"%s\"}".formatted(id, dev)).andExpect(status().isBadRequest()); // ni token, ni código, ni boleto
		scan(g.owner(), g, "{\"scanId\":\"%s\",\"deviceId\":\"%s\",\"token\":\"%s\",\"code\":\"%s\"}".formatted(id, dev, t.token(), t.code())).andExpect(status().isBadRequest());
		scan(g.owner(), g, "{\"scanId\":\"corto\",\"deviceId\":\"%s\",\"token\":\"%s\"}".formatted(dev, t.token())).andExpect(status().isBadRequest());
		scan(g.owner(), g, "{\"scanId\":\"%s\",\"deviceId\":\"%s\",\"sessionId\":\"%s\",\"token\":\"%s\"}".formatted(id, dev, UUID.randomUUID(), t.token())).andExpect(status().isBadRequest());
		assertThat(accepted(t.id())).isZero();
	}

	// ---------- búsqueda manual ----------

	@Test
	void manualCheckIn_byShortCode_andBySearch() throws Exception {
		Gate g = gate();
		String dev = device(g.owner(), g, "Puerta A");
		List<Ticket> tickets = buy(g, type(g, "Pase", List.of(), null, 1, true), 2);
		Ticket a = tickets.get(0);

		// por código corto, como lo teclea una persona (minúsculas, con o sin guion)
		assertAccepted(scan(g.owner(), g, "{\"scanId\":\"%s\",\"deviceId\":\"%s\",\"sessionId\":\"%s\",\"code\":\"%s\"}".formatted(UUID.randomUUID(), dev, g.sessionA(), a.code().toLowerCase().replace("-", " ")))
				.andExpect(jsonPath("$.ticket.id").value(a.id())));
		assertRejected(scan(g.owner(), g, "{\"scanId\":\"%s\",\"deviceId\":\"%s\",\"sessionId\":\"%s\",\"code\":\"%s\"}".formatted(UUID.randomUUID(), dev, g.sessionA(), a.code())), "ALREADY_USED_IN_SESSION");
		assertRejected(scan(g.owner(), g, "{\"scanId\":\"%s\",\"deviceId\":\"%s\",\"code\":\"ZZZZ-ZZZZ\"}".formatted(UUID.randomUUID(), dev)), "TICKET_NOT_FOUND");
		assertRejected(scan(g.owner(), g, "{\"scanId\":\"%s\",\"deviceId\":\"%s\",\"code\":\"abc\"}".formatted(UUID.randomUUID(), dev)), "INVALID_CODE");

		// búsqueda por el correo del comprador y por el nombre del asistente (el correo sale enmascarado)
		String prefix = a.buyerEmail().substring(0, 12);
		String found = mvc.perform(as(g.owner(), get(g.base() + "/attendees").param("q", prefix))).andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2)).andReturn().getResponse().getContentAsString();
		assertThat(JsonPath.<List<String>>read(found, "$[*].buyerEmailMasked")).noneMatch(m -> m.equals(a.buyerEmail()));
		assertThat(JsonPath.<List<String>>read(found, "$[*].buyerEmailMasked")).allMatch(m -> m.contains("***@"));
		mvc.perform(as(g.owner(), get(g.base() + "/attendees").param("q", a.code()))).andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].entries").value(1));

		mvc.perform(as(g.owner(), get(g.base() + "/attendees").param("q", "zz"))).andExpect(status().isBadRequest()); // muy corto

		// ingreso manual por id del boleto encontrado
		List<String> secondIds = JsonPath.read(found, "$[?(@.code=='%s')].ticketId".formatted(tickets.get(1).code()));
		String second = secondIds.getFirst();
		assertAccepted(scan(g.owner(), g, "{\"scanId\":\"%s\",\"deviceId\":\"%s\",\"sessionId\":\"%s\",\"ticketId\":\"%s\"}".formatted(UUID.randomUUID(), dev, g.sessionA(), second)));
	}

	@Test
	void searchByAttendeeName() throws Exception {
		Gate g = gate();
		TestUser buyer = newUser();
		String type = type(g, "Nominativo", List.of(), null, 1, true);
		mvc.perform(as(buyer, post("/api/orders")).header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
				.content("{\"eventSlug\":\"%s\",\"items\":[{\"ticketTypeId\":\"%s\",\"quantity\":1}]}".formatted(g.slug(), type))).andExpect(status().isCreated());
		String ticket = JsonPath.read(mvc.perform(as(buyer, get("/api/tickets"))).andReturn().getResponse().getContentAsString(), "$[0].id");
		mvc.perform(as(buyer, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/tickets/" + ticket + "/attendee")).contentType("application/json")
				.content("{\"name\":\"Valentina Quispe\"}")).andExpect(status().isOk());

		mvc.perform(as(g.owner(), get(g.base() + "/attendees").param("q", "quispe"))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].attendeeName").value("Valentina Quispe"));
		mvc.perform(as(g.owner(), get(g.base() + "/attendees").param("q", "100%"))).andExpect(jsonPath("$.length()").value(0)); // el % no es comodín
	}

	// ---------- reversión, resumen y auditoría ----------

	@Test
	void revert_letsTheTicketEnterAgain_andLeavesATrace() throws Exception {
		Gate g = gate();
		String dev = device(g.owner(), g, "Puerta A");
		Ticket t = buy(g, type(g, "Pase", List.of(), null, 1, false), 1).getFirst();
		String checkIn = JsonPath.read(scanToken(g.owner(), g, dev, t.token(), g.sessionA()).andReturn().getResponse().getContentAsString(), "$.checkInId");
		assertRejected(scanToken(g.owner(), g, dev, t.token(), g.sessionA()), "ALREADY_USED_IN_SESSION");

		mvc.perform(as(g.owner(), post(g.base() + "/checkins/" + checkIn + "/revert")).contentType("application/json").content("{\"reason\":\"Lo escaneó por error\"}"))
				.andExpect(status().isNoContent());
		mvc.perform(as(g.owner(), post(g.base() + "/checkins/" + checkIn + "/revert")).contentType("application/json").content("{\"reason\":\"otra vez\"}"))
				.andExpect(status().isConflict());
		mvc.perform(as(g.owner(), post(g.base() + "/checkins/" + checkIn + "/revert")).contentType("application/json").content("{\"reason\":\"x\"}"))
				.andExpect(status().isBadRequest());

		assertAccepted(scanToken(g.owner(), g, dev, t.token(), g.sessionA())); // vuelve a entrar
		assertRejected(scanToken(g.owner(), g, dev, t.token(), g.sessionA()), "ALREADY_USED_IN_SESSION");
		// la auditoría conserva el ingreso revertido, con quién y por qué
		mvc.perform(as(g.owner(), get(g.base() + "/checkins"))).andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.id=='%s')].revertReason".formatted(checkIn)).value("Lo escaneó por error"))
				.andExpect(jsonPath("$[?(@.id=='%s')].revertedByName".formatted(checkIn)).exists());
		// un rechazo no se puede revertir
		List<String> rejectedIds = JsonPath.read(mvc.perform(as(g.owner(), get(g.base() + "/checkins"))).andReturn().getResponse().getContentAsString(), "$[?(@.result=='REJECTED')].id");
		String rejected = rejectedIds.getFirst();
		mvc.perform(as(g.owner(), post(g.base() + "/checkins/" + rejected + "/revert")).contentType("application/json").content("{\"reason\":\"no aplica\"}"))
				.andExpect(status().isConflict());
	}

	@Test
	void summary_countsCurrentEntriesPerSession() throws Exception {
		Gate g = gate();
		String dev = device(g.owner(), g, "Puerta A");
		List<Ticket> tickets = buy(g, type(g, "Pase", List.of(), null, 1, false), 3);

		assertAccepted(scanToken(g.owner(), g, dev, tickets.get(0).token(), g.sessionA()));
		assertAccepted(scanToken(g.owner(), g, dev, tickets.get(0).token(), g.sessionB()));
		assertAccepted(scanToken(g.owner(), g, dev, tickets.get(1).token(), g.sessionA()));
		assertRejected(scanToken(g.owner(), g, dev, tickets.get(1).token(), g.sessionA()), "ALREADY_USED_IN_SESSION");

		mvc.perform(as(g.owner(), get(g.base() + "/checkins/summary"))).andExpect(status().isOk()).andExpect(jsonPath("$.validTickets").value(3))
				.andExpect(jsonPath("$.ticketsEntered").value(2)).andExpect(jsonPath("$.entries").value(3)).andExpect(jsonPath("$.rejected").value(1))
				.andExpect(jsonPath("$.entriesBySession.%s".formatted(g.sessionA())).value(2)).andExpect(jsonPath("$.entriesBySession.%s".formatted(g.sessionB())).value(1));
	}

	@Test
	void afterTheFirstEntry_theAttendeeNameIsLocked() throws Exception {
		Gate g = gate();
		String dev = device(g.owner(), g, "Puerta A");
		TestUser buyer = newUser();
		String type = type(g, "Nominativo", List.of(), null, 1, true);
		mvc.perform(as(buyer, post("/api/orders")).header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
				.content("{\"eventSlug\":\"%s\",\"items\":[{\"ticketTypeId\":\"%s\",\"quantity\":1}]}".formatted(g.slug(), type))).andExpect(status().isCreated());
		String body = mvc.perform(as(buyer, get("/api/tickets"))).andReturn().getResponse().getContentAsString();
		String ticket = JsonPath.read(body, "$[0].id");
		String token = JsonPath.read(body, "$[0].qrToken");
		var put = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/tickets/" + ticket + "/attendee");

		mvc.perform(as(buyer, put).contentType("application/json").content("{\"name\":\"Primer Nombre\"}")).andExpect(status().isOk());
		assertAccepted(scanToken(g.owner(), g, dev, token, g.sessionA()));
		mvc.perform(as(buyer, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/tickets/" + ticket + "/attendee")).contentType("application/json")
				.content("{\"name\":\"Otro Nombre\"}")).andExpect(status().isConflict());
	}

	@Test
	void auditLog_recordsEveryAttemptWithItsDeviceAndValidator() throws Exception {
		Gate g = gate();
		String dev = device(g.owner(), g, "Puerta Norte");
		Ticket t = buy(g, type(g, "Pase", List.of(), null, 1, false), 1).getFirst();
		assertAccepted(scanToken(g.owner(), g, dev, t.token(), g.sessionA()));
		assertRejected(scanToken(g.owner(), g, dev, t.token(), g.sessionA()), "ALREADY_USED_IN_SESSION");
		assertRejected(scanToken(g.owner(), g, dev, "basura", g.sessionA()), "INVALID_TOKEN");

		mvc.perform(as(g.owner(), get(g.base() + "/checkins"))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3))
				.andExpect(jsonPath("$[0].deviceName").value("Puerta Norte")).andExpect(jsonPath("$[0].validatorName").exists())
				.andExpect(jsonPath("$[?(@.method=='TOKEN')]").isNotEmpty());
		// un código que no corresponde a ningún boleto queda auditado sin boleto asociado
		String audit = mvc.perform(as(g.owner(), get(g.base() + "/checkins"))).andReturn().getResponse().getContentAsString();
		List<java.util.Map<String, Object>> invalid = JsonPath.read(audit, "$[?(@.reason=='INVALID_TOKEN')]");
		assertThat(invalid).hasSize(1);
		assertThat(invalid.getFirst().get("ticketId")).isNull();
		assertThat(invalid.getFirst().get("ticketCode")).isNull();
	}
}
