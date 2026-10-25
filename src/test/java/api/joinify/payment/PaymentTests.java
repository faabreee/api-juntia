package api.joinify.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
import api.joinify.order.OrderService;
import api.joinify.payment.PaymentGateway.Status;

@TestPropertySource(properties = { "app.storage.dir=build/test-uploads", "app.fees.mode=PASSED_TO_BUYER", "app.fees.bps=500",
		"app.fees.fixed-minor=0", "app.payments.reconcile-interval-ms=3600000" })
class PaymentTests extends IntegrationTestSupport {

	@Autowired
	SimulatedGateway gateway;

	@Autowired
	PaymentService paymentService;

	@Autowired
	OrderService orderService;

	@Autowired
	JdbcClient jdbc;

	private record Shop(TestUser owner, String orgId, String eventId, String slug, String typeId) {
		String events() {
			return "/api/organizations/" + orgId + "/events/" + eventId;
		}
	}

	/** Evento publicado con una entrada de S/ 100,00 (10 000) y cupo dado. */
	private Shop shop(int quota) throws Exception {
		return shop(10_000, quota);
	}

	private Shop shop(long priceMinor, int quota) throws Exception {
		TestUser owner = newUser();
		String org = JsonPath.read(mvc.perform(as(owner, post("/api/organizations")).contentType("application/json")
				.content("{\"name\":\"Productora %s\",\"type\":\"COMPANY\",\"country\":\"PE\",\"currency\":\"PEN\",\"timezone\":\"America/Lima\"}"
						.formatted(Math.random()))).andReturn().getResponse().getContentAsString(), "$.id");
		String event = JsonPath.read(mvc.perform(as(owner, post("/api/organizations/" + org + "/events")).contentType("application/json")
				.content("{\"title\":\"Concierto de pagos\",\"timezone\":\"America/Lima\"}")).andReturn().getResponse().getContentAsString(), "$.id");
		Instant start = Instant.now().plus(Duration.ofDays(20));
		mvc.perform(as(owner, post("/api/organizations/" + org + "/events/" + event + "/sessions")).contentType("application/json")
				.content("{\"name\":\"Noche\",\"startsAt\":\"%s\",\"endsAt\":\"%s\"}".formatted(start, start.plus(Duration.ofHours(5)))))
				.andExpect(status().isCreated());
		String type = JsonPath.read(mvc.perform(as(owner, post("/api/organizations/" + org + "/events/" + event + "/ticket-types")).contentType("application/json")
				.content("{\"name\":\"General\",\"template\":\"CUSTOM\",\"priceMinor\":%d,\"quota\":%d,\"maxPerOrder\":%d,\"scope\":\"ALL_SESSIONS\",\"maxTotalUses\":1,\"maxUsesPerSession\":1}"
						.formatted(priceMinor, quota, Math.min(10, quota)))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
		mvc.perform(as(owner, post("/api/organizations/" + org + "/events/" + event + "/publish"))).andExpect(status().isOk());
		String slug = JsonPath.read(mvc.perform(as(owner, get("/api/organizations/" + org + "/events/" + event))).andReturn().getResponse().getContentAsString(), "$.slug");
		return new Shop(owner, org, event, slug, type);
	}

	private String orderOf(Shop shop, TestUser buyer, int quantity) throws Exception {
		return JsonPath.read(mvc.perform(as(buyer, post("/api/orders")).header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType("application/json").content("{\"eventSlug\":\"%s\",\"items\":[{\"ticketTypeId\":\"%s\",\"quantity\":%d}]}".formatted(shop.slug(), shop.typeId(), quantity)))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
	}

	private ResultActions pay(TestUser buyer, String orderId, String key, String token) throws Exception {
		return mvc.perform(as(buyer, post("/api/orders/" + orderId + "/payments")).header("Idempotency-Key", key)
				.contentType("application/json").content("{\"sourceToken\":\"%s\"}".formatted(token)));
	}

	private ResultActions pay(TestUser buyer, String orderId, String token) throws Exception {
		return pay(buyer, orderId, UUID.randomUUID().toString(), token);
	}

	private String paymentId(ResultActions result) throws Exception {
		return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
	}

	/** Los ids de evento deben ser únicos por ejecución: la base conserva los avisos de ejecuciones anteriores. */
	private static final String RUN = UUID.randomUUID().toString().substring(0, 8);

	private static String eventId(String base) {
		return base + "-" + RUN;
	}

	private ResultActions webhook(String eventId, String type, String chargeId) throws Exception {
		String body = "{\"id\":\"%s\",\"type\":\"%s\",\"chargeId\":\"%s\"}".formatted(eventId(eventId), type, chargeId);
		return mvc.perform(post("/api/webhooks/simulated").header("X-Signature", gateway.sign(body)).contentType("application/json").content(body));
	}

	private String providerId(String paymentId) {
		return jdbc.sql("SELECT provider_payment_id FROM payments WHERE id = :id").param("id", paymentId).query(String.class).single();
	}

	private String orderStatus(String orderId) {
		return jdbc.sql("SELECT status FROM orders WHERE id = :id").param("id", orderId).query(String.class).single();
	}

	private String paymentStatus(String paymentId) {
		return jdbc.sql("SELECT status FROM payments WHERE id = :id").param("id", paymentId).query(String.class).single();
	}

	private int taken(Shop shop) {
		return jdbc.sql("SELECT taken FROM ticket_types WHERE id = :id").param("id", shop.typeId()).query(Integer.class).single();
	}

	private long ledger(String paymentId, String kind) {
		return jdbc.sql("SELECT COALESCE(SUM(amount_minor), 0) FROM ledger_entries WHERE payment_id = :id AND kind = :kind")
				.param("id", paymentId).param("kind", kind).query(Long.class).single();
	}

	private long count(String sql, Object param) {
		return jdbc.sql(sql).param("p", param).query(Long.class).single();
	}

	private void expire(String orderId) {
		jdbc.sql("UPDATE orders SET expires_at = :past WHERE id = :id")
				.param("past", LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1)).param("id", orderId).update();
		orderService.expireDue();
	}

	private void ageOpenPaymentRow(String paymentId, int minutes) {
		jdbc.sql("UPDATE payments SET created_at = :t WHERE id = :id")
				.param("t", LocalDateTime.now(ZoneOffset.UTC).minusMinutes(minutes)).param("id", paymentId).update();
	}

	private ResultActions refund(TestUser user, Shop shop, String orderId, String key, String body) throws Exception {
		return mvc.perform(as(user, post("/api/organizations/" + shop.orgId() + "/orders/" + orderId + "/refunds"))
				.header("Idempotency-Key", key).contentType("application/json").content(body));
	}

	private static final String FULL = "{\"reason\":\"REQUESTED_BY_BUYER\"}";

	private static String partial(long amount) {
		return "{\"amountMinor\":%d,\"reason\":\"REQUESTED_BY_BUYER\"}".formatted(amount);
	}

	// ---------- criterio de terminado de la Etapa 5 ----------

	@Test
	void successfulPayment_confirmsTheOrder_andSettlesTheLedger() throws Exception {
		Shop shop = shop(20);
		TestUser buyer = newUser();
		String order = orderOf(shop, buyer, 2); // subtotal 20 000 + comisión 5 % (1 000) = 21 000

		mvc.perform(as(buyer, get("/api/orders/" + order))).andExpect(jsonPath("$.subtotalMinor").value(20_000))
				.andExpect(jsonPath("$.feeMinor").value(1_000)).andExpect(jsonPath("$.totalMinor").value(21_000))
				.andExpect(jsonPath("$.feeMode").value("PASSED_TO_BUYER"));

		ResultActions paid = pay(buyer, order, "tok_success").andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("SUCCEEDED")).andExpect(jsonPath("$.amountMinor").value(21_000));
		String payment = paymentId(paid);

		assertThat(orderStatus(order)).isEqualTo("PAID");
		assertThat(ledger(payment, "ORGANIZER_PAYABLE")).as("el organizador recibe el precio completo").isEqualTo(20_000);
		assertThat(ledger(payment, "PLATFORM_FEE")).as("la plataforma retiene la comisión").isEqualTo(1_000);
		assertThat(paymentService.checkLedger()).isEmpty();
		assertThat(taken(shop)).isEqualTo(2);
		mvc.perform(as(buyer, get("/api/orders/" + order))).andExpect(jsonPath("$.status").value("PAID")).andExpect(jsonPath("$.paidAt").exists());
	}

	@Test
	void declinedPayment_isReportedWithoutBreakingTheOrder_andCanBeRetried() throws Exception {
		Shop shop = shop(5);
		TestUser buyer = newUser();
		String order = orderOf(shop, buyer, 1);

		ResultActions declined = pay(buyer, order, "tok_declined").andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("FAILED"))
				.andExpect(jsonPath("$.failureCode").value("card_declined")).andExpect(jsonPath("$.failureMessage").exists());
		String failed = paymentId(declined);

		assertThat(orderStatus(order)).as("la orden sigue pendiente").isEqualTo("PENDING");
		assertThat(taken(shop)).as("y el cupo sigue reservado").isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM ledger_entries WHERE payment_id = :p", failed)).as("un cobro rechazado no asienta dinero").isZero();

		pay(buyer, order, "tok_insufficient_funds").andExpect(jsonPath("$.status").value("FAILED")).andExpect(jsonPath("$.failureCode").value("insufficient_funds"));
		pay(buyer, order, "tok_success").andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("SUCCEEDED"));

		assertThat(orderStatus(order)).isEqualTo("PAID");
		assertThat(paymentService.checkLedger()).isEmpty();
	}

	@Test
	void duplicateWebhook_isProcessedOnlyOnce() throws Exception {
		Shop shop = shop(5);
		TestUser buyer = newUser();
		String order = orderOf(shop, buyer, 1);
		String payment = paymentId(pay(buyer, order, "tok_pending").andExpect(jsonPath("$.status").value("PENDING")));
		String charge = providerId(payment);
		assertThat(orderStatus(order)).isEqualTo("PENDING");

		gateway.settle(charge, Status.SUCCEEDED); // el banco confirma
		webhook("evt_dup_1", "charge.succeeded", charge).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ok"));

		assertThat(paymentStatus(payment)).isEqualTo("SUCCEEDED");
		assertThat(orderStatus(order)).isEqualTo("PAID");
		assertThat(count("SELECT COUNT(*) FROM ledger_entries WHERE payment_id = :p", payment)).isEqualTo(2);

		// el mismo aviso otra vez (la pasarela reintenta): se reconoce y no se hace nada
		webhook("evt_dup_1", "charge.succeeded", charge).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("duplicate"));
		// y un aviso distinto sobre el mismo cobro tampoco duplica nada
		webhook("evt_dup_2", "charge.succeeded", charge).andExpect(status().isOk());

		assertThat(count("SELECT COUNT(*) FROM ledger_entries WHERE payment_id = :p", payment)).as("el libro no se duplica").isEqualTo(2);
		assertThat(count("SELECT COUNT(*) FROM webhook_events WHERE event_id = :p", eventId("evt_dup_1"))).isEqualTo(1);
		assertThat(taken(shop)).isEqualTo(1);
		assertThat(paymentService.checkLedger()).isEmpty();
	}

	// ---------- webhooks ----------

	@Test
	void webhook_withInvalidSignature_isRejectedAndNotRecorded() throws Exception {
		String body = "{\"id\":\"evt_forged\",\"type\":\"charge.succeeded\",\"chargeId\":\"sim_x\"}";

		mvc.perform(post("/api/webhooks/simulated").header("X-Signature", "00ff").contentType("application/json").content(body))
				.andExpect(status().isUnauthorized());
		mvc.perform(post("/api/webhooks/simulated").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
		assertThat(count("SELECT COUNT(*) FROM webhook_events WHERE event_id = :p", "evt_forged")).isZero();
		mvc.perform(post("/api/webhooks/otraspasarela").header("X-Signature", gateway.sign(body)).contentType("application/json").content(body))
				.andExpect(status().isNotFound());
	}

	@Test
	void webhook_isOnlyANotice_theRealStateIsFetchedFromTheGateway() throws Exception {
		Shop shop = shop(5);
		TestUser buyer = newUser();
		String order = orderOf(shop, buyer, 1);
		String payment = paymentId(pay(buyer, order, "tok_pending"));

		// el aviso dice "exitoso" pero la pasarela todavía no lo confirmó
		webhook("evt_lie", "charge.succeeded", providerId(payment)).andExpect(status().isOk());

		assertThat(paymentStatus(payment)).isEqualTo("PENDING");
		assertThat(orderStatus(order)).isEqualTo("PENDING");
	}

	@Test
	void webhook_forAnUnknownPayment_isAcknowledged() throws Exception {
		webhook("evt_unknown", "charge.succeeded", "sim_no_existe").andExpect(status().isOk());
		assertThat(jdbc.sql("SELECT outcome FROM webhook_events WHERE event_id = :id").param("id", eventId("evt_unknown")).query(String.class).single()).isEqualTo("unknown-payment");
	}

	// ---------- cobro ----------

	@Test
	void samePaymentKey_returnsTheSamePayment_withoutChargingTwice() throws Exception {
		Shop shop = shop(5);
		TestUser buyer = newUser();
		String order = orderOf(shop, buyer, 1);
		String key = "pago-" + UUID.randomUUID();

		String first = paymentId(pay(buyer, order, key, "tok_success").andExpect(status().isCreated()).andExpect(header().string("Idempotent-Replayed", "false")));
		String second = paymentId(pay(buyer, order, key, "tok_success").andExpect(status().isOk()).andExpect(header().string("Idempotent-Replayed", "true")));

		assertThat(second).isEqualTo(first);
		assertThat(count("SELECT COUNT(*) FROM payments WHERE order_id = :p", order)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM ledger_entries WHERE order_id = :p", order)).isEqualTo(2);
	}

	@Test
	void concurrentPaymentAttempts_onlyOneCharges() throws Exception {
		Shop shop = shop(5);
		TestUser buyer = newUser();
		String order = orderOf(shop, buyer, 1);

		ExecutorService pool = Executors.newFixedThreadPool(8);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<Integer>> results = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			results.add(pool.submit(() -> {
				go.await();
				return pay(buyer, order, "tok_success").andReturn().getResponse().getStatus();
			}));
		}
		go.countDown();
		List<Integer> statuses = new ArrayList<>();
		for (Future<Integer> r : results) {
			statuses.add(r.get());
		}
		pool.shutdown();

		assertThat(statuses.stream().filter(s -> s == 201).count()).as("un solo cobro").isEqualTo(1);
		assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(7);
		assertThat(count("SELECT COUNT(*) FROM payments WHERE order_id = :p AND status = 'SUCCEEDED'", order)).isEqualTo(1);
		assertThat(orderStatus(order)).isEqualTo("PAID");
		assertThat(paymentService.checkLedger()).isEmpty();
	}

	@Test
	void paymentIsRefusedWhenOrderIsNotPayable() throws Exception {
		Shop shop = shop(5);
		TestUser buyer = newUser();
		TestUser other = newUser();
		String order = orderOf(shop, buyer, 1);

		pay(other, order, "tok_success").andExpect(status().isNotFound()); // orden ajena
		mvc.perform(post("/api/orders/" + order + "/payments").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
				.header("Idempotency-Key", "sin-sesion-1234").contentType("application/json").content("{\"sourceToken\":\"tok_success\"}"))
				.andExpect(status().isUnauthorized());
		mvc.perform(as(buyer, post("/api/orders/" + order + "/payments")).contentType("application/json").content("{\"sourceToken\":\"tok_success\"}"))
				.andExpect(status().isBadRequest()); // falta la clave
		pay(buyer, order, "corta", "tok_success").andExpect(status().isBadRequest());

		pay(buyer, order, "tok_success").andExpect(status().isCreated());
		pay(buyer, order, "tok_success").andExpect(status().isConflict()); // ya pagada

		String expired = orderOf(shop, buyer, 1);
		expire(expired);
		pay(buyer, expired, "tok_success").andExpect(status().isConflict());
		assertThat(count("SELECT COUNT(*) FROM payments WHERE order_id = :p", expired)).isZero();
	}

	@Test
	void freeOrdersNeedNoPayment_andPayNoFee() throws Exception {
		Shop shop = shop(0, 5);
		TestUser buyer = newUser();
		String order = orderOf(shop, buyer, 2);

		mvc.perform(as(buyer, get("/api/orders/" + order))).andExpect(jsonPath("$.status").value("PAID")).andExpect(jsonPath("$.feeMinor").value(0))
				.andExpect(jsonPath("$.totalMinor").value(0));
		pay(buyer, order, "tok_success").andExpect(status().isConflict());
	}

	// ---------- comisión ----------

	@Test
	void absorbedFee_comesOutOfTheOrganizersShare() throws Exception {
		Shop shop = shop(5);
		jdbc.sql("UPDATE organizations SET platform_fee_mode = 'ABSORBED', platform_fee_bps = 300, platform_fee_fixed_minor = 100 WHERE id = :id")
				.param("id", shop.orgId()).update();
		TestUser buyer = newUser();
		String order = orderOf(shop, buyer, 1); // subtotal 10 000, comisión 3 % + 100 = 400, la absorbe el organizador

		mvc.perform(as(buyer, get("/api/orders/" + order))).andExpect(jsonPath("$.subtotalMinor").value(10_000))
				.andExpect(jsonPath("$.feeMinor").value(400)).andExpect(jsonPath("$.totalMinor").value(10_000)).andExpect(jsonPath("$.feeMode").value("ABSORBED"));
		String payment = paymentId(pay(buyer, order, "tok_success"));

		assertThat(ledger(payment, "ORGANIZER_PAYABLE")).isEqualTo(9_600);
		assertThat(ledger(payment, "PLATFORM_FEE")).isEqualTo(400);
		assertThat(paymentService.checkLedger()).isEmpty();
	}

	// ---------- pago tardío ----------

	@Test
	void latePayment_reopensTheOrder_whenThereIsStillStock() throws Exception {
		Shop shop = shop(5);
		TestUser buyer = newUser();
		String order = orderOf(shop, buyer, 1);
		String payment = paymentId(pay(buyer, order, "tok_pending"));
		expire(order);
		assertThat(orderStatus(order)).isEqualTo("EXPIRED");
		assertThat(taken(shop)).isZero();

		gateway.settle(providerId(payment), Status.SUCCEEDED);
		webhook("evt_late_ok", "charge.succeeded", providerId(payment)).andExpect(status().isOk());

		assertThat(orderStatus(order)).as("el dinero llegó y había cupo: la orden se honra").isEqualTo("PAID");
		assertThat(taken(shop)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM refunds WHERE payment_id = :p", payment)).isZero();
		assertThat(paymentService.checkLedger()).isEmpty();
	}

	@Test
	void latePayment_withoutStock_isRefundedAutomatically() throws Exception {
		Shop shop = shop(1);
		TestUser late = newUser();
		TestUser other = newUser();
		String lateOrder = orderOf(shop, late, 1);
		String payment = paymentId(pay(late, lateOrder, "tok_pending"));
		expire(lateOrder); // la reserva se libera...
		String otherOrder = orderOf(shop, other, 1); // ...y otra persona se queda con el último cupo
		assertThat(taken(shop)).isEqualTo(1);

		gateway.settle(providerId(payment), Status.SUCCEEDED);
		webhook("evt_late_noseat", "charge.succeeded", providerId(payment)).andExpect(status().isOk());

		assertThat(orderStatus(lateOrder)).as("la orden tardía no se honra").isEqualTo("EXPIRED");
		assertThat(orderStatus(otherOrder)).isEqualTo("PENDING");
		assertThat(taken(shop)).as("el cupo sigue siendo de quien lo reservó").isEqualTo(1);
		assertThat(jdbc.sql("SELECT status FROM refunds WHERE payment_id = :p").param("p", payment).query(String.class).single()).isEqualTo("SUCCEEDED");
		assertThat(jdbc.sql("SELECT amount_minor FROM refunds WHERE payment_id = :p").param("p", payment).query(Long.class).single()).isEqualTo(10_500);
		assertThat(ledger(payment, "ORGANIZER_PAYABLE") + ledger(payment, "PLATFORM_FEE")).as("el libro queda en cero").isZero();
		assertThat(paymentService.checkLedger()).isEmpty();
	}

	// ---------- conciliación ----------

	@Test
	void reconciliation_resolvesAPaymentWhoseWebhookWasLost() throws Exception {
		Shop shop = shop(5);
		TestUser buyer = newUser();
		String order = orderOf(shop, buyer, 1);
		String payment = paymentId(pay(buyer, order, "tok_pending"));
		gateway.settle(providerId(payment), Status.SUCCEEDED); // nadie avisó

		assertThat(paymentService.reconcilePending()).as("muy reciente: todavía no se toca").isZero();
		ageOpenPaymentRow(payment, 5);
		assertThat(paymentService.reconcilePending()).isGreaterThanOrEqualTo(1);

		assertThat(paymentStatus(payment)).isEqualTo("SUCCEEDED");
		assertThat(orderStatus(order)).isEqualTo("PAID");
		assertThat(paymentService.reconcilePending()).as("repetir no cambia nada").isZero();
		assertThat(count("SELECT COUNT(*) FROM ledger_entries WHERE payment_id = :p", payment)).isEqualTo(2);
	}

	@Test
	void gatewayTimeout_staysOpenUntilReconciliationGivesUp() throws Exception {
		Shop shop = shop(5);
		TestUser buyer = newUser();
		String order = orderOf(shop, buyer, 1);

		String payment = paymentId(pay(buyer, order, "tok_error").andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING")));
		pay(buyer, order, "tok_success").andExpect(status().isConflict()); // mientras esté abierto no se cobra otra vez

		ageOpenPaymentRow(payment, 5);
		paymentService.reconcilePending();
		assertThat(paymentStatus(payment)).as("aún no pasó el plazo").isEqualTo("PENDING");

		ageOpenPaymentRow(payment, 15);
		paymentService.reconcilePending();
		assertThat(paymentStatus(payment)).isEqualTo("FAILED");
		assertThat(jdbc.sql("SELECT failure_code FROM payments WHERE id = :id").param("id", payment).query(String.class).single()).isEqualTo("no_response");
		pay(buyer, order, "tok_success").andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("SUCCEEDED"));
	}

	@Test
	void ledgerCheck_detectsATamperedLedger() throws Exception {
		Shop shop = shop(5);
		TestUser buyer = newUser();
		String payment = paymentId(pay(buyer, orderOf(shop, buyer, 1), "tok_success"));
		assertThat(paymentService.checkLedger()).isEmpty();

		jdbc.sql("UPDATE ledger_entries SET amount_minor = amount_minor + 1 WHERE payment_id = :p AND kind = 'PLATFORM_FEE'").param("p", payment).update();
		assertThat(paymentService.checkLedger()).anyMatch(p -> p.contains(payment));

		jdbc.sql("UPDATE ledger_entries SET amount_minor = amount_minor - 1 WHERE payment_id = :p AND kind = 'PLATFORM_FEE'").param("p", payment).update();
		assertThat(paymentService.checkLedger()).isEmpty();
	}

	// ---------- reembolsos ----------

	@Test
	void fullRefund_returnsTheMoney_theSeats_andClosesTheLedger() throws Exception {
		Shop shop = shop(5);
		TestUser buyer = newUser();
		String order = orderOf(shop, buyer, 2);
		String payment = paymentId(pay(buyer, order, "tok_success"));
		assertThat(taken(shop)).isEqualTo(2);

		refund(shop.owner(), shop, order, "reembolso-total-1", FULL).andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("SUCCEEDED")).andExpect(jsonPath("$.amountMinor").value(21_000));

		assertThat(orderStatus(order)).isEqualTo("REFUNDED");
		assertThat(taken(shop)).as("las entradas vuelven al inventario").isZero();
		assertThat(ledger(payment, "ORGANIZER_PAYABLE")).isZero();
		assertThat(ledger(payment, "PLATFORM_FEE")).isZero();
		assertThat(paymentService.checkLedger()).isEmpty();

		refund(shop.owner(), shop, order, "reembolso-total-2", FULL).andExpect(status().isConflict()); // no queda nada
		// repetir la misma clave devuelve el mismo reembolso
		refund(shop.owner(), shop, order, "reembolso-total-1", FULL).andExpect(status().isOk()).andExpect(header().string("Idempotent-Replayed", "true"));
		assertThat(count("SELECT COUNT(*) FROM refunds WHERE order_id = :p", order)).isEqualTo(1);
	}

	@Test
	void partialRefunds_splitTheFeeProportionally_andTheLastOneClosesEverything() throws Exception {
		Shop shop = shop(5);
		TestUser buyer = newUser();
		String order = orderOf(shop, buyer, 1); // total 10 500 = 10 000 + 500 de comisión
		String payment = paymentId(pay(buyer, order, "tok_success"));

		refund(shop.owner(), shop, order, "parcial-0001", partial(5_000)).andExpect(status().isCreated()).andExpect(jsonPath("$.amountMinor").value(5_000));

		assertThat(orderStatus(order)).as("reembolso parcial: la orden sigue pagada").isEqualTo("PAID");
		assertThat(taken(shop)).as("y conserva su cupo").isEqualTo(1);
		assertThat(ledger(payment, "PLATFORM_FEE")).as("500 − round(500×5000/10500) = 500 − 238").isEqualTo(262);
		assertThat(ledger(payment, "ORGANIZER_PAYABLE")).isEqualTo(10_000 - 4_762);
		assertThat(paymentService.checkLedger()).isEmpty();

		refund(shop.owner(), shop, order, "parcial-0002", partial(5_500)).andExpect(status().isCreated()); // lo que queda

		assertThat(orderStatus(order)).isEqualTo("REFUNDED");
		assertThat(taken(shop)).isZero();
		assertThat(ledger(payment, "PLATFORM_FEE")).isZero();
		assertThat(ledger(payment, "ORGANIZER_PAYABLE")).isZero();
		assertThat(paymentService.checkLedger()).isEmpty();
	}

	@Test
	void refundValidation_andPermissions() throws Exception {
		Shop shop = shop(5);
		TestUser buyer = newUser();
		TestUser member = newUser();
		TestUser outsider = newUser();
		mvc.perform(as(shop.owner(), post("/api/organizations/" + shop.orgId() + "/invitations")).contentType("application/json")
				.content("{\"email\":\"%s\",\"role\":\"MEMBER\"}".formatted(member.email()))).andExpect(status().isCreated());
		mvc.perform(as(member, post("/api/invitations/accept")).contentType("application/json")
				.content("{\"token\":\"%s\"}".formatted(lastTokenSentTo(member.email())))).andExpect(status().isOk());
		String order = orderOf(shop, buyer, 1);
		String unpaid = orderOf(shop, buyer, 1);
		pay(buyer, order, "tok_success").andExpect(status().isCreated());

		refund(shop.owner(), shop, order, "validacion-1", partial(10_501)).andExpect(status().isConflict()); // más de lo cobrado
		refund(shop.owner(), shop, order, "validacion-2", "{\"amountMinor\":0,\"reason\":\"OTHER\"}").andExpect(status().isBadRequest());
		refund(shop.owner(), shop, order, "validacion-3", "{\"amountMinor\":100}").andExpect(status().isBadRequest()); // falta el motivo
		refund(shop.owner(), shop, unpaid, "validacion-4", FULL).andExpect(status().isConflict()); // sin pago confirmado

		refund(member, shop, order, "permisos-0001", FULL).andExpect(status().isForbidden());
		refund(outsider, shop, order, "permisos-0002", FULL).andExpect(status().isNotFound());
		refund(buyer, shop, order, "permisos-0003", FULL).andExpect(status().isNotFound()); // el comprador no es de la organización
		mvc.perform(post("/api/organizations/" + shop.orgId() + "/orders/" + order + "/refunds")
				.with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
				.header("Idempotency-Key", "sin-sesion-1234").contentType("application/json").content(FULL)).andExpect(status().isUnauthorized());

		// una organización ajena no puede reembolsar órdenes de otra
		Shop other = shop(5);
		refund(other.owner(), other, order, "ajena-0001", FULL).andExpect(status().isNotFound());
		assertThat(orderStatus(order)).isEqualTo("PAID");
	}

	@Test
	void refundRejectedByTheGateway_changesNothing() throws Exception {
		Shop shop = shop(5);
		TestUser buyer = newUser();
		String order = orderOf(shop, buyer, 1);
		String payment = paymentId(pay(buyer, order, "tok_success_norefund"));

		refund(shop.owner(), shop, order, "rechazado-001", FULL).andExpect(status().isBadGateway());

		assertThat(orderStatus(order)).isEqualTo("PAID");
		assertThat(taken(shop)).isEqualTo(1);
		assertThat(jdbc.sql("SELECT refunded_minor FROM payments WHERE id = :id").param("id", payment).query(Long.class).single()).as("se devolvió la reserva").isZero();
		assertThat(jdbc.sql("SELECT status FROM refunds WHERE payment_id = :p").param("p", payment).query(String.class).single()).isEqualTo("FAILED");
		assertThat(paymentService.checkLedger()).isEmpty();
	}

	@Test
	void concurrentRefunds_neverExceedWhatWasCharged() throws Exception {
		Shop shop = shop(5);
		TestUser buyer = newUser();
		String order = orderOf(shop, buyer, 1); // 10 500
		String payment = paymentId(pay(buyer, order, "tok_success"));

		ExecutorService pool = Executors.newFixedThreadPool(6);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<Integer>> results = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			String key = "carrera-" + i + "-" + UUID.randomUUID();
			results.add(pool.submit(() -> {
				go.await();
				return refund(shop.owner(), shop, order, key.substring(0, 40), partial(4_000)).andReturn().getResponse().getStatus();
			}));
		}
		go.countDown();
		List<Integer> statuses = new ArrayList<>();
		for (Future<Integer> r : results) {
			statuses.add(r.get());
		}
		pool.shutdown();

		assertThat(statuses.stream().filter(s -> s == 201).count()).as("4 000 × 2 = 8 000 ≤ 10 500; el tercero no cabe").isEqualTo(2);
		assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(4);
		assertThat(jdbc.sql("SELECT refunded_minor FROM payments WHERE id = :id").param("id", payment).query(Long.class).single()).isEqualTo(8_000);
		assertThat(ledger(payment, "ORGANIZER_PAYABLE") + ledger(payment, "PLATFORM_FEE")).isEqualTo(2_500);
		assertThat(paymentService.checkLedger()).isEmpty();
	}

	// ---------- panel de ventas y configuración pública ----------

	@Test
	void salesList_showsTheBreakdownToManagersOnly() throws Exception {
		Shop shop = shop(5);
		TestUser buyer = newUser();
		String order = orderOf(shop, buyer, 1);
		pay(buyer, order, "tok_success").andExpect(status().isCreated());
		refund(shop.owner(), shop, order, "ventas-0001", partial(2_000)).andExpect(status().isCreated());

		mvc.perform(as(shop.owner(), get("/api/organizations/" + shop.orgId() + "/sales"))).andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].orderId").value(order))
				.andExpect(jsonPath("$[0].buyerEmail").value(buyer.email())).andExpect(jsonPath("$[0].subtotalMinor").value(10_000))
				.andExpect(jsonPath("$[0].feeMinor").value(500)).andExpect(jsonPath("$[0].totalMinor").value(10_500))
				.andExpect(jsonPath("$[0].refundedMinor").value(2_000)).andExpect(jsonPath("$[0].refundableMinor").value(8_500));
		mvc.perform(as(buyer, get("/api/organizations/" + shop.orgId() + "/sales"))).andExpect(status().isNotFound());
		mvc.perform(get("/api/organizations/" + shop.orgId() + "/sales")).andExpect(status().isUnauthorized());
	}

	@Test
	void paymentConfig_exposesNoSecrets() throws Exception {
		TestUser user = newUser();
		mvc.perform(as(user, get("/api/payments/config"))).andExpect(status().isOk()).andExpect(jsonPath("$.provider").value("simulated"))
				.andExpect(jsonPath("$.publicKey").doesNotExist());
	}
}
