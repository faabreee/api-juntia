package api.joinify.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

import com.jayway.jsonpath.JsonPath;

import api.joinify.IntegrationTestSupport;

@TestPropertySource(properties = "app.storage.dir=build/test-uploads")
class OrderTests extends IntegrationTestSupport {

	@Autowired
	OrderService orderService;

	@Autowired
	JdbcClient jdbc;

	private record Fixture(TestUser owner, String orgId, String eventId, String slug) {
		String events() {
			return "/api/organizations/" + orgId + "/events/" + eventId;
		}
	}

	private Fixture publishedEvent() throws Exception {
		TestUser owner = newUser();
		String org = JsonPath.read(mvc.perform(as(owner, post("/api/organizations")).contentType("application/json")
				.content("{\"name\":\"Productora %s\",\"type\":\"COMPANY\",\"country\":\"PE\",\"currency\":\"PEN\",\"timezone\":\"America/Lima\"}"
						.formatted(Math.random()))).andReturn().getResponse().getContentAsString(), "$.id");
		String event = JsonPath.read(mvc.perform(as(owner, post("/api/organizations/" + org + "/events")).contentType("application/json")
				.content("{\"title\":\"Concierto de órdenes\",\"timezone\":\"America/Lima\"}")).andReturn().getResponse().getContentAsString(), "$.id");
		Instant start = Instant.now().plus(Duration.ofDays(20));
		mvc.perform(as(owner, post("/api/organizations/" + org + "/events/" + event + "/sessions")).contentType("application/json")
				.content("{\"name\":\"Noche\",\"startsAt\":\"%s\",\"endsAt\":\"%s\"}".formatted(start, start.plus(Duration.ofHours(5)))))
				.andExpect(status().isCreated());
		String slug = JsonPath.read(mvc.perform(as(owner, get("/api/organizations/" + org + "/events/" + event))).andReturn().getResponse().getContentAsString(), "$.slug");
		return new Fixture(owner, org, event, slug);
	}

	private void publish(Fixture f) throws Exception {
		mvc.perform(as(f.owner(), post(f.events() + "/publish"))).andExpect(status().isOk());
	}

	/** Entrada a todas las sesiones. quota null = ilimitado. */
	private String ticketType(Fixture f, String name, long priceMinor, Integer quota, int maxPerOrder) throws Exception {
		return JsonPath.read(mvc.perform(as(f.owner(), post(f.events() + "/ticket-types")).contentType("application/json")
				.content("{\"name\":\"%s\",\"template\":\"CUSTOM\",\"priceMinor\":%d,\"quota\":%s,\"maxPerOrder\":%d,\"scope\":\"ALL_SESSIONS\",\"maxTotalUses\":1,\"maxUsesPerSession\":1}"
						.formatted(name, priceMinor, quota, maxPerOrder)))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
	}

	private String body(Fixture f, String... typeAndQty) {
		StringBuilder items = new StringBuilder();
		for (int i = 0; i < typeAndQty.length; i += 2) {
			if (i > 0) {
				items.append(',');
			}
			items.append("{\"ticketTypeId\":\"%s\",\"quantity\":%s}".formatted(typeAndQty[i], typeAndQty[i + 1]));
		}
		return "{\"eventSlug\":\"%s\",\"items\":[%s]}".formatted(f.slug(), items);
	}

	private org.springframework.test.web.servlet.ResultActions order(TestUser buyer, String key, String body) throws Exception {
		return mvc.perform(as(buyer, post("/api/orders")).header("Idempotency-Key", key).contentType("application/json").content(body));
	}

	private org.springframework.test.web.servlet.ResultActions order(TestUser buyer, String body) throws Exception {
		return order(buyer, UUID.randomUUID().toString(), body);
	}

	private String createOrder(TestUser buyer, String body) throws Exception {
		return JsonPath.read(order(buyer, body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
	}

	private int taken(String ticketTypeId) {
		return jdbc.sql("SELECT taken FROM ticket_types WHERE id = :id").param("id", ticketTypeId).query(Integer.class).single();
	}

	private void expireNow(String orderId) {
		jdbc.sql("UPDATE orders SET expires_at = :past WHERE id = :id")
				.param("past", java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1)).param("id", orderId).update();
	}

	private String statusOf(TestUser buyer, String orderId) throws Exception {
		return JsonPath.read(mvc.perform(as(buyer, get("/api/orders/" + orderId))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.status");
	}

	// ---------- criterio de terminado de la Etapa 4 ----------

	@Test
	void fiftyBuyersCompeteForTheLastTenSeats_exactlyTenOrdersSucceed() throws Exception {
		Fixture f = publishedEvent();
		String type = ticketType(f, "Últimos cupos", 5000, 10, 4);
		publish(f);
		List<TestUser> buyers = new ArrayList<>();
		for (int i = 0; i < 50; i++) {
			buyers.add(newUser());
		}

		ExecutorService pool = Executors.newFixedThreadPool(50);
		CountDownLatch ready = new CountDownLatch(50);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<Integer>> results = new ArrayList<>();
		for (TestUser buyer : buyers) {
			Callable<Integer> task = () -> {
				ready.countDown();
				go.await();
				return order(buyer, body(f, type, "1")).andReturn().getResponse().getStatus();
			};
			results.add(pool.submit(task));
		}
		ready.await();
		go.countDown(); // todos a la vez
		List<Integer> statuses = new ArrayList<>();
		for (Future<Integer> r : results) {
			statuses.add(r.get());
		}
		pool.shutdown();

		assertThat(statuses.stream().filter(s -> s == 201).count()).as("órdenes creadas").isEqualTo(10);
		assertThat(statuses.stream().filter(s -> s == 409).count()).as("rechazadas por agotado").isEqualTo(40);
		assertThat(statuses).containsOnly(201, 409);
		assertThat(taken(type)).isEqualTo(10);
		assertThat(jdbc.sql("SELECT COUNT(*) FROM orders WHERE event_id = :e AND status = 'PENDING'").param("e", f.eventId()).query(Long.class).single()).isEqualTo(10);
		assertThat(jdbc.sql("SELECT COALESCE(SUM(oi.quantity), 0) FROM order_items oi JOIN orders o ON o.id = oi.order_id WHERE o.event_id = :e").param("e", f.eventId()).query(Long.class).single()).isEqualTo(10);
	}

	@Test
	void concurrentOrdersOfSeveralUnits_neverOversell() throws Exception {
		Fixture f = publishedEvent();
		String type = ticketType(f, "Cupo 10", 1000, 10, 4);
		publish(f);
		List<TestUser> buyers = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			buyers.add(newUser());
		}
		ExecutorService pool = Executors.newFixedThreadPool(12);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<Integer>> results = new ArrayList<>();
		for (TestUser buyer : buyers) {
			results.add(pool.submit(() -> {
				go.await();
				return order(buyer, body(f, type, "3")).andReturn().getResponse().getStatus();
			}));
		}
		go.countDown();
		long created = 0;
		for (Future<Integer> r : results) {
			if (r.get() == 201) {
				created++;
			}
		}
		pool.shutdown();

		assertThat(created).isEqualTo(3); // 3 x 3 = 9 de 10; el cuarto no cabe
		assertThat(taken(type)).isEqualTo(9);
		// la unidad sobrante todavía se puede comprar
		createOrder(newUser(), body(f, type, "1"));
		assertThat(taken(type)).isEqualTo(10);
		order(newUser(), body(f, type, "1")).andExpect(status().isConflict());
	}

	// ---------- idempotencia ----------

	@Test
	void sameKeySameRequest_returnsTheSameOrder() throws Exception {
		Fixture f = publishedEvent();
		String type = ticketType(f, "General", 2000, 50, 10);
		publish(f);
		TestUser buyer = newUser();
		String key = "orden-" + UUID.randomUUID();

		String first = order(buyer, key, body(f, type, "2")).andExpect(status().isCreated())
				.andExpect(header().string("Idempotent-Replayed", "false")).andReturn().getResponse().getContentAsString();
		String second = order(buyer, key, body(f, type, "2")).andExpect(status().isOk())
				.andExpect(header().string("Idempotent-Replayed", "true")).andReturn().getResponse().getContentAsString();

		assertThat(JsonPath.<String>read(second, "$.id")).isEqualTo(JsonPath.<String>read(first, "$.id"));
		assertThat(taken(type)).isEqualTo(2); // no se reservó dos veces
		mvc.perform(as(buyer, get("/api/orders"))).andExpect(jsonPath("$.length()").value(1));
	}

	@Test
	void sameKeyDifferentRequest_isRejected() throws Exception {
		Fixture f = publishedEvent();
		String type = ticketType(f, "General", 2000, 50, 10);
		publish(f);
		TestUser buyer = newUser();
		String key = "orden-" + UUID.randomUUID();

		order(buyer, key, body(f, type, "2")).andExpect(status().isCreated());
		order(buyer, key, body(f, type, "3")).andExpect(status().isConflict());
		assertThat(taken(type)).isEqualTo(2);
	}

	@Test
	void sameKeySentConcurrently_createsExactlyOneOrder() throws Exception {
		Fixture f = publishedEvent();
		String type = ticketType(f, "General", 2000, 50, 10);
		publish(f);
		TestUser buyer = newUser();
		String key = "carrera-" + UUID.randomUUID();

		ExecutorService pool = Executors.newFixedThreadPool(10);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<String>> results = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			results.add(pool.submit(() -> {
				go.await();
				var response = order(buyer, key, body(f, type, "2")).andReturn().getResponse();
				return response.getStatus() + ":" + JsonPath.<String>read(response.getContentAsString(), "$.id");
			}));
		}
		go.countDown();
		List<String> outcomes = new ArrayList<>();
		for (Future<String> r : results) {
			outcomes.add(r.get());
		}
		pool.shutdown();

		assertThat(outcomes.stream().map(o -> o.substring(o.indexOf(':') + 1)).distinct()).hasSize(1);
		assertThat(outcomes.stream().filter(o -> o.startsWith("201")).count()).isEqualTo(1);
		assertThat(outcomes.stream().filter(o -> o.startsWith("200")).count()).isEqualTo(9);
		assertThat(taken(type)).isEqualTo(2);
	}

	@Test
	void missingOrMalformedKey_isRejected() throws Exception {
		Fixture f = publishedEvent();
		String type = ticketType(f, "General", 2000, 50, 10);
		publish(f);
		TestUser buyer = newUser();

		mvc.perform(as(buyer, post("/api/orders")).contentType("application/json").content(body(f, type, "1"))).andExpect(status().isBadRequest());
		order(buyer, "corta", body(f, type, "1")).andExpect(status().isBadRequest());
		assertThat(taken(type)).isZero();
	}

	// ---------- validaciones ----------

	@Test
	void multiLineOrder_isAtomic() throws Exception {
		Fixture f = publishedEvent();
		String plenty = ticketType(f, "Abundante", 1000, 5, 5);
		String scarce = ticketType(f, "Escasa", 1000, 1, 1);
		publish(f);
		createOrder(newUser(), body(f, scarce, "1")); // agota la escasa

		order(newUser(), body(f, plenty, "2", scarce, "1")).andExpect(status().isConflict());

		assertThat(taken(plenty)).as("la línea que sí cabía no debe quedar reservada").isZero();
		assertThat(taken(scarce)).isEqualTo(1);
		assertThat(jdbc.sql("SELECT COUNT(*) FROM orders WHERE event_id = :e").param("e", f.eventId()).query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void multiLineOrder_reservesEveryLine_andTotalsCorrectly() throws Exception {
		Fixture f = publishedEvent();
		String a = ticketType(f, "A", 1500, 10, 5);
		String b = ticketType(f, "B", 4000, 10, 5);
		publish(f);

		order(newUser(), body(f, a, "2", b, "1")).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.totalMinor").value(7000)).andExpect(jsonPath("$.currency").value("PEN"))
				.andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.expiresAt").exists());
		assertThat(taken(a)).isEqualTo(2);
		assertThat(taken(b)).isEqualTo(1);
	}

	@Test
	void quantityLimitsAndShape_areValidated() throws Exception {
		Fixture f = publishedEvent();
		String type = ticketType(f, "General", 2000, 50, 3);
		publish(f);
		TestUser buyer = newUser();

		order(buyer, body(f, type, "4")).andExpect(status().isBadRequest()); // más del máximo por orden
		order(buyer, body(f, type, "0")).andExpect(status().isBadRequest());
		order(buyer, "{\"eventSlug\":\"%s\",\"items\":[]}".formatted(f.slug())).andExpect(status().isBadRequest());
		order(buyer, body(f, type, "1", type, "1")).andExpect(status().isBadRequest()); // tipo repetido
		order(buyer, body(f, UUID.randomUUID().toString(), "1")).andExpect(status().isNotFound());
		assertThat(taken(type)).isZero();
	}

	@Test
	void salesWindow_hiddenTypes_andEventState_areEnforced() throws Exception {
		Fixture f = publishedEventWithoutPublishing();
		Instant now = Instant.now();
		String notStarted = typeWithWindow(f, "Pronto", "\"salesStart\":\"%s\"".formatted(now.plus(Duration.ofDays(2))), true);
		String ended = typeWithWindow(f, "Terminó", "\"salesStart\":\"%s\",\"salesEnd\":\"%s\"".formatted(now.minus(Duration.ofDays(3)), now.minus(Duration.ofDays(1))), true);
		String hidden = typeWithWindow(f, "Oculta", "\"salesStart\":\"%s\"".formatted(now.minus(Duration.ofDays(1))), false);
		String open = ticketType(f, "Abierta", 1000, 5, 5);
		TestUser buyer = newUser();

		// borrador: no existe para el comprador
		order(buyer, body(f, open, "1")).andExpect(status().isNotFound());
		publish(f);

		order(buyer, body(f, notStarted, "1")).andExpect(status().isConflict());
		order(buyer, body(f, ended, "1")).andExpect(status().isConflict());
		order(buyer, body(f, hidden, "1")).andExpect(status().isNotFound());
		createOrder(buyer, body(f, open, "1"));

		// cancelado: ya no vende
		mvc.perform(as(f.owner(), post(f.events() + "/cancel"))).andExpect(status().isOk());
		order(newUser(), body(f, open, "1")).andExpect(status().isConflict());
	}

	private Fixture publishedEventWithoutPublishing() throws Exception {
		return publishedEvent();
	}

	private String typeWithWindow(Fixture f, String name, String windowJson, boolean active) throws Exception {
		return JsonPath.read(mvc.perform(as(f.owner(), post(f.events() + "/ticket-types")).contentType("application/json")
				.content("{\"name\":\"%s\",\"template\":\"CUSTOM\",\"priceMinor\":1000,\"scope\":\"ALL_SESSIONS\",\"quota\":5,\"maxPerOrder\":5,%s,\"active\":%s}"
						.formatted(name, windowJson, active))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
	}

	@Test
	void unlimitedQuota_alwaysSells() throws Exception {
		Fixture f = publishedEvent();
		String type = ticketType(f, "Sin límite", 500, null, 50);
		publish(f);

		for (int i = 0; i < 5; i++) {
			order(newUser(), body(f, type, "50")).andExpect(status().isCreated());
		}
		assertThat(taken(type)).isEqualTo(250);
	}

	@Test
	void requiresLogin_andOrdersArePrivate() throws Exception {
		Fixture f = publishedEvent();
		String type = ticketType(f, "General", 2000, 50, 10);
		publish(f);
		TestUser buyer = newUser();
		TestUser other = newUser();
		String id = createOrder(buyer, body(f, type, "1"));

		mvc.perform(post("/api/orders").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()).header("Idempotency-Key", "sin-sesion-1234").contentType("application/json").content(body(f, type, "1")))
				.andExpect(status().isUnauthorized());
		mvc.perform(get("/api/orders/" + id)).andExpect(status().isUnauthorized());
		mvc.perform(as(other, get("/api/orders/" + id))).andExpect(status().isNotFound());
		mvc.perform(as(other, post("/api/orders/" + id + "/cancel"))).andExpect(status().isNotFound());
		mvc.perform(as(other, get("/api/orders"))).andExpect(jsonPath("$.length()").value(0));
		mvc.perform(as(buyer, get("/api/orders"))).andExpect(jsonPath("$.length()").value(1));
		assertThat(statusOf(buyer, id)).isEqualTo("PENDING");
	}

	// ---------- ciclo de vida ----------

	@Test
	void freeOrder_completesWithoutPayment_andKeepsTheSeat() throws Exception {
		Fixture f = publishedEvent();
		String free = ticketType(f, "Invitación", 0, 5, 2);
		publish(f);
		TestUser buyer = newUser();

		String json = order(buyer, body(f, free, "2")).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PAID"))
				.andExpect(jsonPath("$.totalMinor").value(0)).andExpect(jsonPath("$.paidAt").exists())
				.andExpect(jsonPath("$.expiresAt").doesNotExist()).andReturn().getResponse().getContentAsString();
		String id = JsonPath.read(json, "$.id");

		assertThat(taken(free)).isEqualTo(2);
		assertThat(orderService.expireDue()).isGreaterThanOrEqualTo(0);
		assertThat(statusOf(buyer, id)).isEqualTo("PAID");
		assertThat(taken(free)).as("una orden pagada nunca devuelve su cupo").isEqualTo(2);
		mvc.perform(as(buyer, post("/api/orders/" + id + "/cancel"))).andExpect(status().isConflict());
	}

	@Test
	void unpaidOrder_expires_andReleasesTheSeats() throws Exception {
		Fixture f = publishedEvent();
		String type = ticketType(f, "Único cupo", 3000, 1, 1);
		publish(f);
		TestUser buyer = newUser();
		TestUser waiting = newUser();
		String id = createOrder(buyer, body(f, type, "1"));
		order(waiting, body(f, type, "1")).andExpect(status().isConflict()); // agotado mientras está reservado
		assertThat(taken(type)).isEqualTo(1);

		expireNow(id);
		orderService.expireDue();

		assertThat(statusOf(buyer, id)).isEqualTo("EXPIRED");
		assertThat(taken(type)).isZero();
		orderService.expireDue(); // idempotente: no devuelve el cupo dos veces
		assertThat(taken(type)).isZero();
		createOrder(waiting, body(f, type, "1")); // el cupo liberado se vende
		assertThat(taken(type)).isEqualTo(1);
	}

	@Test
	void expiry_releasesEveryLineOfTheOrder() throws Exception {
		Fixture f = publishedEvent();
		String a = ticketType(f, "A", 1000, 10, 5);
		String b = ticketType(f, "B", 1000, 10, 5);
		publish(f);
		String id = createOrder(newUser(), body(f, a, "3", b, "2"));
		assertThat(taken(a)).isEqualTo(3);
		assertThat(taken(b)).isEqualTo(2);

		expireNow(id);
		orderService.expireDue();

		assertThat(taken(a)).isZero();
		assertThat(taken(b)).isZero();
	}

	@Test
	void buyerCancelsPendingOrder_andSeatsReturn() throws Exception {
		Fixture f = publishedEvent();
		String type = ticketType(f, "General", 2000, 5, 5);
		publish(f);
		TestUser buyer = newUser();
		String id = createOrder(buyer, body(f, type, "3"));
		assertThat(taken(type)).isEqualTo(3);

		mvc.perform(as(buyer, post("/api/orders/" + id + "/cancel"))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
		assertThat(taken(type)).isZero();
		mvc.perform(as(buyer, post("/api/orders/" + id + "/cancel"))).andExpect(status().isConflict());
		assertThat(taken(type)).as("cancelar dos veces no devuelve cupo de más").isZero();
	}

	@Test
	void confirmedPayment_keepsSeats_andLateConfirmationIsRefused() throws Exception {
		Fixture f = publishedEvent();
		String type = ticketType(f, "General", 2000, 5, 5);
		publish(f);
		TestUser buyer = newUser();
		String paid = createOrder(buyer, body(f, type, "2"));
		String late = createOrder(buyer, body(f, type, "1"));

		assertThat(orderService.confirmPayment(paid)).isTrue();
		assertThat(orderService.confirmPayment(paid)).as("segunda confirmación: sin efecto").isFalse();
		assertThat(statusOf(buyer, paid)).isEqualTo("PAID");

		expireNow(late);
		orderService.expireDue();
		assertThat(orderService.confirmPayment(late)).as("orden ya expirada").isFalse();
		assertThat(statusOf(buyer, late)).isEqualTo("EXPIRED");
		assertThat(taken(type)).as("solo queda la orden pagada").isEqualTo(2);
		mvc.perform(as(buyer, post("/api/orders/" + paid + "/cancel"))).andExpect(status().isConflict());
	}

	// ---------- reglas que protegen lo ya vendido (módulo de eventos) ----------

	@Test
	void soldTickets_protectTheTicketType_andTheEvent() throws Exception {
		Fixture f = publishedEvent();
		String type = ticketType(f, "General", 2000, 10, 5);
		publish(f);
		String id = createOrder(newUser(), body(f, type, "3"));
		String typesUrl = f.events() + "/ticket-types/" + type;
		String sameRule = "\"scope\":\"ALL_SESSIONS\",\"maxTotalUses\":1,\"maxUsesPerSession\":1";

		// no se puede cambiar la regla, ni bajar el cupo por debajo de lo tomado, ni borrar, ni despublicar
		mvc.perform(as(f.owner(), put(typesUrl)).contentType("application/json")
				.content("{\"name\":\"General\",\"template\":\"CUSTOM\",\"priceMinor\":2000,\"quota\":10,\"scope\":\"ALL_SESSIONS\",\"maxTotalUses\":5,\"maxUsesPerSession\":1}"))
				.andExpect(status().isConflict());
		mvc.perform(as(f.owner(), put(typesUrl)).contentType("application/json")
				.content("{\"name\":\"General\",\"template\":\"CUSTOM\",\"priceMinor\":2000,\"quota\":2,\"maxPerOrder\":2,%s}".formatted(sameRule)))
				.andExpect(status().isConflict());
		mvc.perform(as(f.owner(), delete(typesUrl))).andExpect(status().isConflict());
		mvc.perform(as(f.owner(), post(f.events() + "/unpublish"))).andExpect(status().isConflict());

		// precio, nombre, cupo mayor y visibilidad sí; la orden existente conserva su foto
		mvc.perform(as(f.owner(), put(typesUrl)).contentType("application/json")
				.content("{\"name\":\"General plus\",\"template\":\"CUSTOM\",\"priceMinor\":9000,\"quota\":20,%s,\"active\":true}".formatted(sameRule)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.taken").value(3));
		mvc.perform(as(buyerOf(id), get("/api/orders/" + id))).andExpect(status().isOk());
		assertThat(jdbc.sql("SELECT unit_price_minor FROM order_items WHERE order_id = :id").param("id", id).query(Long.class).single()).isEqualTo(2000);
		assertThat(jdbc.sql("SELECT ticket_type_name FROM order_items WHERE order_id = :id").param("id", id).query(String.class).single()).isEqualTo("General");
	}

	private TestUser buyerOf(String orderId) {
		String buyerId = jdbc.sql("SELECT buyer_user_id FROM orders WHERE id = :id").param("id", orderId).query(String.class).single();
		String email = jdbc.sql("SELECT email FROM users WHERE id = :id").param("id", buyerId).query(String.class).single();
		return knownUsers.get(email);
	}

	private final java.util.Map<String, TestUser> knownUsers = new java.util.concurrent.ConcurrentHashMap<>();

	@Override
	protected TestUser newUser(String email) throws Exception {
		TestUser user = super.newUser(email);
		knownUsers.put(email, user);
		return user;
	}

	@Test
	void publicPage_marksSoldOutTicketTypes() throws Exception {
		Fixture f = publishedEvent();
		String type = ticketType(f, "Pocas", 1000, 2, 2);
		publish(f);

		mvc.perform(get("/api/public/events/" + f.slug())).andExpect(jsonPath("$.ticketTypes[0].soldOut").value(false));
		createOrder(newUser(), body(f, type, "2"));
		mvc.perform(get("/api/public/events/" + f.slug())).andExpect(jsonPath("$.ticketTypes[0].soldOut").value(true));
	}
}
