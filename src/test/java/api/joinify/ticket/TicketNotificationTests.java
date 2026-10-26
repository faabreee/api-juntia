package api.joinify.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

import com.jayway.jsonpath.JsonPath;

import api.joinify.IntegrationTestSupport;
import api.joinify.notification.NotificationDispatcher;
import api.joinify.notification.NotificationService;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;

@TestPropertySource(properties = { "app.storage.dir=build/test-uploads", "app.fees.mode=PASSED_TO_BUYER", "app.fees.bps=500", "app.fees.fixed-minor=0",
		"app.payments.reconcile-interval-ms=3600000", "app.tickets.repair-interval-ms=3600000" })
class TicketNotificationTests extends IntegrationTestSupport {

	private static final String TITLE = "Fiesta <b>& Co";

	@Autowired
	JdbcClient jdbc;

	@Autowired
	NotificationDispatcher dispatcher;

	@Autowired
	NotificationService queue;

	@Autowired
	TicketNotifications notifications;

	@Autowired
	TicketIssuer issuer;

	private record Shop(TestUser owner, String orgId, String eventId, String slug, String sessionA, String sessionB, String allType, String onlyBType) {
		String base() {
			return "/api/organizations/" + orgId + "/events/" + eventId;
		}
	}

	/** Evento publicado con dos sesiones (A dentro de 20 h, B dentro de 30 h) y dos tipos de entrada. */
	private Shop shop(long priceMinor) throws Exception {
		TestUser owner = newUser();
		String org = JsonPath.read(mvc.perform(as(owner, post("/api/organizations")).contentType("application/json")
				.content("{\"name\":\"Productora %s\",\"type\":\"COMPANY\",\"country\":\"PE\",\"currency\":\"PEN\",\"timezone\":\"America/Lima\"}".formatted(Math.random())))
				.andReturn().getResponse().getContentAsString(), "$.id");
		String event = JsonPath.read(mvc.perform(as(owner, post("/api/organizations/" + org + "/events")).contentType("application/json")
				.content("{\"title\":\"%s\",\"timezone\":\"America/Lima\"}".formatted(TITLE))).andReturn().getResponse().getContentAsString(), "$.id");
		Instant now = Instant.now();
		String a = session(owner, org, event, "Día A", now.plus(Duration.ofHours(20)), now.plus(Duration.ofHours(26)));
		String b = session(owner, org, event, "Día B", now.plus(Duration.ofHours(30)), now.plus(Duration.ofHours(36)));
		String all = type(owner, org, event, "Pase todo", priceMinor, "ALL_SESSIONS", "[]");
		String onlyB = type(owner, org, event, "Solo B", priceMinor, "SELECTED_SESSIONS", "[\"" + b + "\"]");
		mvc.perform(as(owner, post("/api/organizations/" + org + "/events/" + event + "/publish"))).andExpect(status().isOk());
		String slug = JsonPath.read(mvc.perform(as(owner, get("/api/organizations/" + org + "/events/" + event))).andReturn().getResponse().getContentAsString(), "$.slug");
		return new Shop(owner, org, event, slug, a, b, all, onlyB);
	}

	private String session(TestUser owner, String org, String event, String name, Instant start, Instant end) throws Exception {
		return JsonPath.read(mvc.perform(as(owner, post("/api/organizations/" + org + "/events/" + event + "/sessions")).contentType("application/json")
				.content("{\"name\":\"%s\",\"startsAt\":\"%s\",\"endsAt\":\"%s\"}".formatted(name, start, end))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
	}

	private String type(TestUser owner, String org, String event, String name, long price, String scope, String sessions) throws Exception {
		return JsonPath.read(mvc.perform(as(owner, post("/api/organizations/" + org + "/events/" + event + "/ticket-types")).contentType("application/json")
				.content("{\"name\":\"%s\",\"template\":\"CUSTOM\",\"priceMinor\":%d,\"quota\":40,\"maxPerOrder\":10,\"scope\":\"%s\",\"sessionIds\":%s,\"maxUsesPerSession\":1}"
						.formatted(name, price, scope, sessions))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
	}

	private String buy(Shop shop, TestUser buyer, String typeId, int quantity) throws Exception {
		String order = JsonPath.read(mvc.perform(as(buyer, post("/api/orders")).header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
				.content("{\"eventSlug\":\"%s\",\"items\":[{\"ticketTypeId\":\"%s\",\"quantity\":%d}]}".formatted(shop.slug(), typeId, quantity))).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString(), "$.id");
		if (jdbc.sql("SELECT total_minor FROM orders WHERE id = :id").param("id", order).query(Long.class).single() > 0) {
			mvc.perform(as(buyer, post("/api/orders/" + order + "/payments")).header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
					.content("{\"sourceToken\":\"tok_success\"}")).andExpect(status().isCreated());
		}
		return order;
	}

	// ---------- utilidades de lectura ----------

	private List<Map<String, Object>> rows(String type, String eventId) {
		return jdbc.sql("SELECT id, status, recipient_email, attempts, subject, last_error, next_attempt_at FROM notifications WHERE type = :t AND event_id = :e ORDER BY created_at, id")
				.param("t", type).param("e", eventId).query().listOfRows();
	}

	private void awaitAllSent(String type, String eventId, int expected) {
		org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> {
			var r = rows(type, eventId);
			return r.size() == expected && r.stream().allMatch(x -> "SENT".equals(x.get("status")));
		});
	}

	private void makeAllDue(String type, String eventId) {
		jdbc.sql("UPDATE notifications SET next_attempt_at = :t WHERE type = :type AND event_id = :e AND status = 'PENDING'")
				.param("t", LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1)).param("type", type).param("e", eventId).update();
	}

	private List<MimeMessage> mailsTo(String email, String subjectStart) {
		return org.mockito.Mockito.mockingDetails(mailSender).getInvocations().stream()
				.filter(i -> i.getMethod().getName().equals("send") && i.getArguments().length == 1 && i.getArguments()[0] instanceof MimeMessage)
				.map(i -> (MimeMessage) i.getArguments()[0]).filter(m -> {
					try {
						return m.getAllRecipients() != null && m.getAllRecipients()[0].toString().equals(email) && m.getSubject().startsWith(subjectStart);
					} catch (jakarta.mail.MessagingException e) {
						return false;
					}
				}).toList();
	}

	private String content(MimeMessage message) throws Exception {
		StringBuilder out = new StringBuilder();
		walk(message.getContent(), out);
		return out.toString();
	}

	private void walk(Object content, StringBuilder out) throws Exception {
		if (content instanceof String s) {
			out.append(s).append('\n');
		} else if (content instanceof Multipart multipart) {
			for (int i = 0; i < multipart.getCount(); i++) {
				var part = multipart.getBodyPart(i);
				if (!Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) {
					walk(part.getContent(), out);
				}
			}
		}
	}

	private void agedTickets(String orderId) {
		jdbc.sql("UPDATE tickets SET issued_at = :t WHERE order_id = :id").param("t", LocalDateTime.now(ZoneOffset.UTC).minusHours(5)).param("id", orderId).update();
	}

	// ---------- confirmación de compra ----------

	@Test
	void aPaidOrder_sendsAConfirmationWithTheBreakdown() throws Exception {
		Shop shop = shop(8_000);
		TestUser buyer = newUser();
		String order = buy(shop, buyer, shop.allType(), 2); // 16 000 + 5 % (800)

		awaitAllSent("ORDER_CONFIRMATION", shop.eventId(), 1);

		MimeMessage mail = mailsTo(buyer.email(), "Confirmación de compra").getFirst();
		assertThat(mail.getSubject()).isEqualTo("Confirmación de compra: " + TITLE);
		String body = content(mail);
		assertThat(body).contains("2 × Pase todo").contains("160.00").contains("Cargo por servicio").contains("8.00").contains("168.00").contains("/orders/" + order);
		// el título con HTML llega escapado en la versión HTML y literal en el texto
		assertThat(body).contains("Fiesta &lt;b&gt;&amp; Co").contains("Fiesta <b>& Co");
		assertThat(body).doesNotContain("<b>& Co</");
	}

	@Test
	void aFreeOrder_isConfirmedAsAReservation() throws Exception {
		Shop shop = shop(0);
		TestUser buyer = newUser();
		buy(shop, buyer, shop.allType(), 1);

		awaitAllSent("ORDER_CONFIRMATION", shop.eventId(), 1);

		MimeMessage mail = mailsTo(buyer.email(), "Reserva confirmada").getFirst();
		assertThat(content(mail)).contains("Tu reserva está confirmada").doesNotContain("Total:");
	}

	@Test
	void aConfirmation_forAnOrderRefundedBeforeSending_isSkipped() throws Exception {
		Shop shop = shop(8_000);
		TestUser buyer = newUser();
		String order = buy(shop, buyer, shop.allType(), 1);
		awaitAllSent("ORDER_CONFIRMATION", shop.eventId(), 1);
		mvc.perform(as(shop.owner(), post(shop.base().replace("/events/" + shop.eventId(), "") + "/orders/" + order + "/refunds"))
				.header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json").content("{\"reason\":\"OTHER\"}")).andExpect(status().isCreated());

		String id = queue.enqueue(new NotificationService.Request("ORDER_CONFIRMATION", buyer.email(), null, shop.orgId(), shop.eventId(), Map.of("orderId", order),
				"otra-" + UUID.randomUUID(), Instant.now().plus(Duration.ofHours(1)))).orElseThrow();
		jdbc.sql("UPDATE notifications SET next_attempt_at = :t WHERE id = :id").param("t", LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1)).param("id", id).update();
		dispatcher.dispatchDue(500);

		assertThat(jdbc.sql("SELECT status FROM notifications WHERE id = :id").param("id", id).query(String.class).single()).isEqualTo("SKIPPED");
	}

	// ---------- entrega y reenvío de boletos ----------

	@Test
	void ticketDelivery_isQueuedOncePerOrder_andCanBeResent() throws Exception {
		Shop shop = shop(0);
		TestUser buyer = newUser();
		String order = buy(shop, buyer, shop.allType(), 2);
		awaitAllSent("TICKETS_DELIVERY", shop.eventId(), 1);

		assertThat(issuer.issue(order)).isZero(); // nada nuevo que emitir: no hay otro correo
		assertThat(rows("TICKETS_DELIVERY", shop.eventId())).hasSize(1);
		assertThat(mailsTo(buyer.email(), "Tus boletos para")).hasSize(1);

		// el comprador pide reenviar (límite de 3 por hora)
		for (int i = 0; i < 3; i++) {
			mvc.perform(as(buyer, post("/api/orders/" + order + "/resend-tickets"))).andExpect(status().isAccepted());
		}
		mvc.perform(as(buyer, post("/api/orders/" + order + "/resend-tickets"))).andExpect(status().isTooManyRequests());
		awaitAllSent("TICKETS_DELIVERY", shop.eventId(), 4);
		assertThat(mailsTo(buyer.email(), "Tus boletos para")).hasSize(4);

		// el organizador también puede reenviar, con su propio límite
		mvc.perform(as(shop.owner(), post("/api/organizations/" + shop.orgId() + "/orders/" + order + "/resend-tickets"))).andExpect(status().isAccepted());
		awaitAllSent("TICKETS_DELIVERY", shop.eventId(), 5);
	}

	@Test
	void resend_isRestrictedAndRefusedWithoutValidTickets() throws Exception {
		Shop shop = shop(0);
		Shop other = shop(0);
		TestUser buyer = newUser();
		TestUser stranger = newUser();
		TestUser member = newUser();
		String order = buy(shop, buyer, shop.allType(), 1);
		mvc.perform(as(shop.owner(), post("/api/organizations/" + shop.orgId() + "/invitations")).contentType("application/json")
				.content("{\"email\":\"%s\",\"role\":\"MEMBER\"}".formatted(member.email()))).andExpect(status().isCreated());
		mvc.perform(as(member, post("/api/invitations/accept")).contentType("application/json").content("{\"token\":\"%s\"}".formatted(lastTokenSentTo(member.email())))).andExpect(status().isOk());

		mvc.perform(as(stranger, post("/api/orders/" + order + "/resend-tickets"))).andExpect(status().isNotFound());
		mvc.perform(post("/api/orders/" + order + "/resend-tickets").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())).andExpect(status().isUnauthorized());
		mvc.perform(as(member, post("/api/organizations/" + shop.orgId() + "/orders/" + order + "/resend-tickets"))).andExpect(status().isForbidden());
		mvc.perform(as(other.owner(), post("/api/organizations/" + other.orgId() + "/orders/" + order + "/resend-tickets"))).andExpect(status().isNotFound());

		// sin boletos vigentes (orden reembolsada) no hay nada que reenviar
		jdbc.sql("UPDATE tickets SET status = 'VOID' WHERE order_id = :id").param("id", order).update();
		mvc.perform(as(buyer, post("/api/orders/" + order + "/resend-tickets"))).andExpect(status().isConflict());
	}

	// ---------- recordatorio previo ----------

	@Test
	void reminders_goOutOnceForSessionsStartingInTheNext24Hours_thatTheTicketsCover() throws Exception {
		Shop shop = shop(0);
		TestUser both = newUser();
		TestUser onlyB = newUser();
		TestUser fresh = newUser();
		String orderAll = buy(shop, both, shop.allType(), 2);
		String orderB = buy(shop, onlyB, shop.onlyBType(), 1);
		String orderFresh = buy(shop, fresh, shop.allType(), 1);
		agedTickets(orderAll);
		agedTickets(orderB);
		// "fresh" compró hace instantes: ya tiene su confirmación, no se le recuerda de inmediato

		int queued = notifications.enqueueReminders();

		assertThat(queued).isEqualTo(1);
		var rows = rows("EVENT_REMINDER", shop.eventId());
		assertThat(rows).hasSize(1);
		assertThat(rows.getFirst().get("recipient_email")).isEqualTo(both.email()); // solo B está a 30 h: fuera del horizonte; "fresh" es reciente
		assertThat(notifications.enqueueReminders()).as("repetir el barrido no duplica").isZero();

		awaitAllSent("EVENT_REMINDER", shop.eventId(), 1);
		String body = content(mailsTo(both.email(), "Recordatorio").getFirst());
		assertThat(body).contains("Día A").contains("2 boletos").doesNotContain("Día B");
		assertThat(orderFresh).isNotNull();
		assertThat(orderB).isNotNull();
	}

	@Test
	void aReminder_isSkippedIfTheEventWasCancelledMeanwhile() throws Exception {
		Shop shop = shop(0);
		TestUser buyer = newUser();
		agedTickets(buy(shop, buyer, shop.allType(), 1));
		jdbc.sql("UPDATE notifications SET status = 'SENT'  WHERE event_id = :e").param("e", shop.eventId()).update(); // ignorar confirmaciones previas

		notifications.enqueueReminders();
		jdbc.sql("UPDATE notifications SET next_attempt_at = :t WHERE type = 'EVENT_REMINDER' AND event_id = :e").param("t", LocalDateTime.now(ZoneOffset.UTC).plusHours(1)).param("e", shop.eventId()).update();
		org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> rows("EVENT_REMINDER", shop.eventId()).size() == 1);
		jdbc.sql("UPDATE notifications SET status = 'PENDING', attempts = 0 WHERE type = 'EVENT_REMINDER' AND event_id = :e").param("e", shop.eventId()).update();
		jdbc.sql("UPDATE events SET status = 'CANCELLED' WHERE id = :e").param("e", shop.eventId()).update();
		makeAllDue("EVENT_REMINDER", shop.eventId());

		dispatcher.dispatchDue(500);

		assertThat(rows("EVENT_REMINDER", shop.eventId()).getFirst().get("status")).isEqualTo("SKIPPED");
	}

	// ---------- evento cancelado ----------

	@Test
	void cancellingAnEvent_notifiesEveryHolderOnce_withTheReason() throws Exception {
		Shop shop = shop(0);
		TestUser a = newUser();
		TestUser b = newUser();
		TestUser refunded = newUser();
		buy(shop, a, shop.allType(), 2); // dos boletos de la misma persona: un solo aviso
		buy(shop, b, shop.allType(), 1);
		String gone = buy(shop, refunded, shop.allType(), 1);
		jdbc.sql("UPDATE tickets SET status = 'VOID' WHERE order_id = :id").param("id", gone).update();

		mvc.perform(as(shop.owner(), post(shop.base() + "/cancel")).contentType("application/json").content("{\"reason\":\"Lluvia <fuerte>\"}")).andExpect(status().isOk());

		awaitAllSent("EVENT_CANCELLED", shop.eventId(), 2);
		var recipients = rows("EVENT_CANCELLED", shop.eventId()).stream().map(r -> (String) r.get("recipient_email")).toList();
		assertThat(recipients).containsExactlyInAnyOrder(a.email(), b.email());
		String body = content(mailsTo(a.email(), "Evento cancelado").getFirst());
		assertThat(body).contains("Lluvia <fuerte>").contains("Lluvia &lt;fuerte&gt;").contains("/orders");
		assertThat(mailsTo(refunded.email(), "Evento cancelado")).isEmpty();
	}

	// ---------- evento modificado ----------

	@Test
	void changingASession_notifiesHoldersOnce_afterAShortDelay_withTheFinalSchedule() throws Exception {
		Shop shop = shop(0);
		TestUser a = newUser();
		TestUser b = newUser();
		buy(shop, a, shop.allType(), 1);
		buy(shop, b, shop.allType(), 1);
		Instant newStart = Instant.now().plus(Duration.ofHours(48));

		mvc.perform(as(shop.owner(), put(shop.base() + "/sessions/" + shop.sessionA())).contentType("application/json")
				.content("{\"name\":\"Día A\",\"startsAt\":\"%s\",\"endsAt\":\"%s\"}".formatted(newStart, newStart.plus(Duration.ofHours(6))))).andExpect(status().isOk());
		var first = rows("EVENT_CHANGED", shop.eventId());
		assertThat(first).hasSize(2);
		assertThat(first).allMatch(r -> "PENDING".equals(r.get("status")));
		LocalDateTime next = (LocalDateTime) first.getFirst().get("next_attempt_at");
		assertThat(Duration.between(LocalDateTime.now(ZoneOffset.UTC), next)).isBetween(Duration.ofMinutes(9), Duration.ofMinutes(11)); // sale en ~10 min

		// otra edición dentro de la ventana: no genera más correos
		mvc.perform(as(shop.owner(), put(shop.base() + "/sessions/" + shop.sessionA())).contentType("application/json")
				.content("{\"name\":\"Día A (nuevo nombre)\",\"startsAt\":\"%s\",\"endsAt\":\"%s\"}".formatted(newStart, newStart.plus(Duration.ofHours(6))))).andExpect(status().isOk());
		assertThat(rows("EVENT_CHANGED", shop.eventId())).hasSize(2);
		assertThat(mailsTo(a.email(), "Cambios en")).isEmpty();

		makeAllDue("EVENT_CHANGED", shop.eventId());
		dispatcher.dispatchDue(500);

		assertThat(rows("EVENT_CHANGED", shop.eventId())).allMatch(r -> "SENT".equals(r.get("status")));
		String body = content(mailsTo(a.email(), "Cambios en").getFirst());
		assertThat(body).contains("Día A (nuevo nombre)").contains("siguen siendo válidos");
	}

	@Test
	void onlyRelevantChanges_triggerAnEmail() throws Exception {
		Shop shop = shop(0);
		buy(shop, newUser(), shop.allType(), 1);
		String same = "{\"title\":\"%s\",\"description\":\"%s\",\"timezone\":\"America/Lima\"}";

		// solo la descripción: no hace falta avisar
		mvc.perform(as(shop.owner(), put(shop.base()).contentType("application/json").content(same.formatted(TITLE.replace("\"", ""), "Texto nuevo")))).andExpect(status().isOk());
		assertThat(rows("EVENT_CHANGED", shop.eventId())).isEmpty();
		// el título sí
		mvc.perform(as(shop.owner(), put(shop.base()).contentType("application/json").content(same.formatted("Otro título", "Texto nuevo")))).andExpect(status().isOk());
		assertThat(rows("EVENT_CHANGED", shop.eventId())).hasSize(1);
	}

	@Test
	void changesToADraft_orToAnEventWithoutHolders_sendNothing() throws Exception {
		Shop draft = shop(0);
		mvc.perform(as(draft.owner(), put(draft.base() + "/sessions/" + draft.sessionA())).contentType("application/json")
				.content("{\"name\":\"Sin boletos\",\"startsAt\":\"%s\",\"endsAt\":\"%s\"}".formatted(Instant.now().plus(Duration.ofHours(60)), Instant.now().plus(Duration.ofHours(66))))).andExpect(status().isOk());
		assertThat(rows("EVENT_CHANGED", draft.eventId())).isEmpty(); // publicado pero sin titulares

		mvc.perform(as(draft.owner(), post(draft.base() + "/unpublish"))).andExpect(status().isOk());
		mvc.perform(as(draft.owner(), put(draft.base() + "/sessions/" + draft.sessionA())).contentType("application/json")
				.content("{\"name\":\"Borrador\",\"startsAt\":\"%s\",\"endsAt\":\"%s\"}".formatted(Instant.now().plus(Duration.ofHours(61)), Instant.now().plus(Duration.ofHours(67))))).andExpect(status().isOk());
		assertThat(rows("EVENT_CHANGED", draft.eventId())).isEmpty();
	}

	@Test
	void changingTheVenue_notifiesHoldersOfEventsThatUseIt() throws Exception {
		Shop shop = shop(0);
		TestUser buyer = newUser();
		String venue = JsonPath.read(mvc.perform(as(shop.owner(), post("/api/organizations/" + shop.orgId() + "/venues")).contentType("application/json")
				.content("{\"name\":\"Arena Vieja\",\"city\":\"Lima\",\"country\":\"PE\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
		Instant start = Instant.now().plus(Duration.ofHours(22));
		mvc.perform(as(shop.owner(), put(shop.base() + "/sessions/" + shop.sessionA())).contentType("application/json")
				.content("{\"name\":\"Día A\",\"startsAt\":\"%s\",\"endsAt\":\"%s\",\"venueId\":\"%s\"}".formatted(start, start.plus(Duration.ofHours(5)), venue))).andExpect(status().isOk());
		buy(shop, buyer, shop.allType(), 1);
		jdbc.sql("DELETE FROM notifications WHERE type = 'EVENT_CHANGED' AND event_id = :e").param("e", shop.eventId()).update();

		mvc.perform(as(shop.owner(), put("/api/organizations/" + shop.orgId() + "/venues/" + venue)).contentType("application/json")
				.content("{\"name\":\"Arena Nueva\",\"city\":\"Lima\",\"country\":\"PE\"}")).andExpect(status().isOk());
		assertThat(rows("EVENT_CHANGED", shop.eventId())).hasSize(1);
		makeAllDue("EVENT_CHANGED", shop.eventId());
		dispatcher.dispatchDue(500);
		assertThat(content(mailsTo(buyer.email(), "Cambios en").getFirst())).contains("Arena Nueva");
	}
}
