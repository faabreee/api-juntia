package api.joinify.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

import com.jayway.jsonpath.JsonPath;

import api.joinify.IntegrationTestSupport;
import api.joinify.notification.NotificationRenderer.Rendered;
import jakarta.mail.Multipart;
import jakarta.mail.internet.MimeMessage;

@TestPropertySource(properties = { "app.storage.dir=build/test-uploads", "app.payments.reconcile-interval-ms=3600000", "app.tickets.repair-interval-ms=3600000" })
class NotificationQueueTests extends IntegrationTestSupport {

	/** Plantilla de prueba: el parámetro "mode" decide qué hace (ok, fail, skip o flaky:N = falla N veces y luego envía). */
	@TestConfiguration
	static class Config {
		static final Map<String, AtomicInteger> FAILURES = new ConcurrentHashMap<>();

		@Bean
		NotificationRenderer testRenderer() {
			return new NotificationRenderer() {
				@Override
				public String type() {
					return "TEST_ECHO";
				}

				@Override
				public Rendered render(Map<String, String> params) {
					String mode = params.getOrDefault("mode", "ok");
					if (mode.equals("fail")) {
						throw new IllegalStateException("fallo de plantilla simulado");
					}
					if (mode.equals("skip")) {
						throw new SkipNotificationException("ya no corresponde");
					}
					if (mode.equals("flaky")) {
						int remaining = Integer.parseInt(params.get("failures"));
						if (FAILURES.computeIfAbsent(params.get("key"), k -> new AtomicInteger()).incrementAndGet() <= remaining) {
							throw new IllegalStateException("SMTP no disponible (simulado)");
						}
					}
					return new Rendered("Asunto " + params.getOrDefault("name", "x"), "Hola " + params.getOrDefault("name", "x"),
							"<p>Hola " + EmailLayout.esc(params.getOrDefault("name", "x")) + "</p>");
				}
			};
		}
	}

	@Autowired
	NotificationService service;

	@Autowired
	NotificationDispatcher dispatcher;

	@Autowired
	JdbcClient jdbc;

	private static String recipient() {
		return "dest-" + UUID.randomUUID() + "@example.com";
	}

	/** Encola para el futuro (sin intento inmediato) y la deja lista para el despachador. */
	private String queueDue(String email, Map<String, String> params) {
		String id = service.enqueue(new NotificationService.Request("TEST_ECHO", email, null, null, null, params, null, Instant.now().plus(Duration.ofHours(1)))).orElseThrow();
		makeDue(id);
		return id;
	}

	private void makeDue(String id) {
		jdbc.sql("UPDATE notifications SET next_attempt_at = :t WHERE id = :id").param("t", LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1)).param("id", id).update();
	}

	private Map<String, Object> row(String id) {
		return jdbc.sql("SELECT status, attempts, last_error, subject, sent_at, next_attempt_at FROM notifications WHERE id = :id").param("id", id).query().singleRow();
	}

	private List<MimeMessage> sentTo(String email) {
		return org.mockito.Mockito.mockingDetails(mailSender).getInvocations().stream()
				.filter(i -> i.getMethod().getName().equals("send") && i.getArguments().length == 1 && i.getArguments()[0] instanceof MimeMessage)
				.map(i -> (MimeMessage) i.getArguments()[0]).filter(m -> {
					try {
						return m.getAllRecipients() != null && m.getAllRecipients()[0].toString().equals(email);
					} catch (jakarta.mail.MessagingException e) {
						return false;
					}
				}).toList();
	}

	// ---------- envío y registro ----------

	@Test
	void anEnqueuedNotification_isSentRightAway_asTextAndHtml_andLogged() throws Exception {
		String email = recipient();

		String id = service.enqueue(new NotificationService.Request("TEST_ECHO", email, null, null, null, Map.of("name", "Ana <b>"), null, null)).orElseThrow();

		org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> "SENT".equals(row(id).get("status")));
		MimeMessage message = sentTo(email).getFirst();
		assertThat(message.getSubject()).isEqualTo("Asunto Ana <b>");
		Multipart multipart = (Multipart) message.getContent();
		StringBuilder all = new StringBuilder();
		collect(multipart, all);
		assertThat(all.toString()).contains("Hola Ana <b>").contains("<p>Hola Ana &lt;b&gt;</p>"); // texto plano y HTML escapado
		assertThat(row(id).get("attempts")).isEqualTo(1);
		assertThat(row(id).get("subject")).isEqualTo("Asunto Ana <b>");
		assertThat(row(id).get("sent_at")).isNotNull();
	}

	private static void collect(Multipart multipart, StringBuilder out) throws Exception {
		for (int i = 0; i < multipart.getCount(); i++) {
			var part = multipart.getBodyPart(i);
			if (part.getContent() instanceof Multipart nested) {
				collect(nested, out);
			} else if (part.getContent() instanceof String s) {
				out.append(s).append('\n');
			}
		}
	}

	@Test
	void sameDedupeKey_isQueuedOnlyOnce() {
		String email = recipient();
		String key = "dedupe-" + UUID.randomUUID();
		var request = new NotificationService.Request("TEST_ECHO", email, null, null, null, Map.of("name", "A"), key, Instant.now().plus(Duration.ofHours(1)));

		assertThat(service.enqueue(request)).isPresent();
		assertThat(service.enqueue(request)).isEmpty();

		assertThat(jdbc.sql("SELECT COUNT(*) FROM notifications WHERE dedupe_key = :k").param("k", key).query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void aFutureNotification_waitsForItsTime() {
		String email = recipient();
		String id = service.enqueue(new NotificationService.Request("TEST_ECHO", email, null, null, null, Map.of("name", "A"), null, Instant.now().plus(Duration.ofMinutes(10)))).orElseThrow();

		dispatcher.dispatchDue(500);

		assertThat(row(id).get("status")).isEqualTo("PENDING");
		assertThat(sentTo(email)).isEmpty();
		makeDue(id);
		dispatcher.dispatchDue(500);
		assertThat(row(id).get("status")).isEqualTo("SENT");
		assertThat(sentTo(email)).hasSize(1);
	}

	// ---------- reintentos ----------

	@Test
	void failures_areRetriedWithGrowingWaits_untilTheyGiveUp() {
		String id = queueDue(recipient(), Map.of("mode", "fail"));
		long[] minutes = { 1, 5, 30, 120, 360 };

		for (int attempt = 1; attempt <= 4; attempt++) {
			dispatcher.dispatchDue(500);
			var r = row(id);
			assertThat(r.get("status")).as("intento %d", attempt).isEqualTo("PENDING");
			assertThat(r.get("attempts")).isEqualTo(attempt);
			assertThat(r.get("last_error")).asString().contains("fallo de plantilla simulado");
			// la próxima hora de intento es "ahora + espera" (con margen de unos segundos)
			LocalDateTime next = (LocalDateTime) r.get("next_attempt_at");
			Duration wait = Duration.between(LocalDateTime.now(ZoneOffset.UTC), next);
			assertThat(wait).isBetween(Duration.ofMinutes(minutes[attempt - 1]).minusSeconds(30), Duration.ofMinutes(minutes[attempt - 1]).plusSeconds(5));
			makeDue(id);
		}
		dispatcher.dispatchDue(500); // quinto intento: se rinde
		var last = row(id);
		assertThat(last.get("status")).isEqualTo("FAILED");
		assertThat(last.get("attempts")).isEqualTo(5);
		makeDue(id);
		dispatcher.dispatchDue(500);
		assertThat(row(id).get("attempts")).as("un envío fallido no se reintenta solo").isEqualTo(5);
	}

	@Test
	void aFlakyMailServer_eventuallyDelivers_exactlyOnce() {
		String email = recipient();
		String id = queueDue(email, Map.of("mode", "flaky", "failures", "2", "key", UUID.randomUUID().toString(), "name", "Flaky"));

		dispatcher.dispatchDue(500);
		assertThat(row(id).get("status")).isEqualTo("PENDING");
		makeDue(id);
		dispatcher.dispatchDue(500);
		assertThat(row(id).get("status")).isEqualTo("PENDING");
		makeDue(id);
		dispatcher.dispatchDue(500);

		assertThat(row(id).get("status")).isEqualTo("SENT");
		assertThat(row(id).get("attempts")).isEqualTo(3);
		assertThat(sentTo(email)).hasSize(1);
	}

	@Test
	void whenTheMailServerRejects_theSendIsRetriedLater() {
		String email = recipient();
		String id = queueDue(email, Map.of("name", "SMTP"));
		org.mockito.Mockito.doThrow(new org.springframework.mail.MailSendException("Connection refused")).doNothing().when(mailSender).send(org.mockito.ArgumentMatchers.any(MimeMessage.class));

		dispatcher.dispatchDue(500);
		assertThat(row(id).get("status")).isEqualTo("PENDING");
		assertThat(row(id).get("last_error")).asString().contains("MailSendException");
		makeDue(id);
		dispatcher.dispatchDue(500);

		assertThat(row(id).get("status")).isEqualTo("SENT");
		assertThat(row(id).get("attempts")).isEqualTo(2);
	}

	// ---------- concurrencia y alquiler ----------

	@Test
	void severalDispatchersAtOnce_sendEachNotificationExactlyOnce() throws Exception {
		List<String> emails = new ArrayList<>();
		List<String> ids = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			String email = recipient();
			emails.add(email);
			ids.add(queueDue(email, Map.of("name", "N" + i)));
		}

		ExecutorService pool = Executors.newFixedThreadPool(8);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<Integer>> futures = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			futures.add(pool.submit(() -> {
				go.await();
				return dispatcher.dispatchDue(500);
			}));
		}
		go.countDown();
		for (Future<Integer> f : futures) {
			f.get();
		}
		pool.shutdown();

		for (int i = 0; i < ids.size(); i++) {
			assertThat(sentTo(emails.get(i))).as("correo %d", i).hasSize(1);
			assertThat(row(ids.get(i)).get("status")).isEqualTo("SENT");
			assertThat(row(ids.get(i)).get("attempts")).isEqualTo(1);
		}
	}

	@Test
	void anAbandonedSend_isTakenOverOnlyWhenItsLeaseExpires() {
		String email = recipient();
		String id = queueDue(email, Map.of("name", "Lease"));
		// alguien lo tomó hace un momento y sigue dentro de su alquiler
		jdbc.sql("UPDATE notifications SET status = 'SENDING', attempts = 1, lease_until = :t WHERE id = :id").param("t", LocalDateTime.now(ZoneOffset.UTC).plusMinutes(2)).param("id", id).update();
		dispatcher.dispatchDue(500);
		assertThat(row(id).get("status")).isEqualTo("SENDING");
		assertThat(sentTo(email)).isEmpty();

		// quien lo tomó cayó: el alquiler venció y otro lo retoma
		jdbc.sql("UPDATE notifications SET lease_until = :t WHERE id = :id").param("t", LocalDateTime.now(ZoneOffset.UTC).minusSeconds(5)).param("id", id).update();
		dispatcher.dispatchDue(500);

		assertThat(row(id).get("status")).isEqualTo("SENT");
		assertThat(row(id).get("attempts")).isEqualTo(2);
		assertThat(sentTo(email)).hasSize(1);
	}

	// ---------- casos especiales ----------

	@Test
	void aTemplateCanSkipAnObsoleteNotification() {
		String email = recipient();
		String id = queueDue(email, Map.of("mode", "skip"));

		dispatcher.dispatchDue(500);

		assertThat(row(id).get("status")).isEqualTo("SKIPPED");
		assertThat(row(id).get("last_error")).isEqualTo("ya no corresponde");
		assertThat(sentTo(email)).isEmpty();
	}

	@Test
	void anUnknownType_failsWithoutRetrying() {
		String id = service.enqueue(new NotificationService.Request("NO_EXISTE", recipient(), null, null, null, Map.of(), null, Instant.now().plus(Duration.ofHours(1)))).orElseThrow();
		makeDue(id);

		dispatcher.dispatchDue(500);

		assertThat(row(id).get("status")).isEqualTo("FAILED");
		assertThat(row(id).get("last_error")).asString().contains("NO_EXISTE");
	}

	// ---------- registro y reintento del organizador ----------

	private record Org(TestUser owner, String orgId, String eventId) {
	}

	private Org org() throws Exception {
		TestUser owner = newUser();
		String org = JsonPath.read(mvc.perform(as(owner, post("/api/organizations")).contentType("application/json")
				.content("{\"name\":\"Productora %s\",\"type\":\"COMPANY\",\"country\":\"PE\",\"currency\":\"PEN\",\"timezone\":\"America/Lima\"}".formatted(Math.random())))
				.andReturn().getResponse().getContentAsString(), "$.id");
		String event = JsonPath.read(mvc.perform(as(owner, post("/api/organizations/" + org + "/events")).contentType("application/json")
				.content("{\"title\":\"Evento de correos\",\"timezone\":\"America/Lima\"}")).andReturn().getResponse().getContentAsString(), "$.id");
		return new Org(owner, org, event);
	}

	private String queueFor(Org o, Map<String, String> params) {
		String id = service.enqueue(new NotificationService.Request("TEST_ECHO", recipient(), null, o.orgId(), o.eventId(), params, null, Instant.now().plus(Duration.ofHours(1)))).orElseThrow();
		makeDue(id);
		return id;
	}

	@Test
	void organizers_seeTheSendLog_andCanRetryFailedOnes() throws Exception {
		Org o = org();
		String okId = queueFor(o, Map.of("name", "Bien"));
		String badId = queueFor(o, Map.of("mode", "fail"));
		dispatcher.dispatchDue(500);
		jdbc.sql("UPDATE notifications SET status = 'FAILED', attempts = 5 WHERE id = :id").param("id", badId).update();

		String body = mvc.perform(as(o.owner(), get("/api/organizations/" + o.orgId() + "/events/" + o.eventId() + "/notifications"))).andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2)).andReturn().getResponse().getContentAsString();
		assertThat(JsonPath.<List<String>>read(body, "$[?(@.id=='%s')].status".formatted(okId))).containsExactly("SENT");
		assertThat(JsonPath.<List<String>>read(body, "$[?(@.id=='%s')].status".formatted(badId))).containsExactly("FAILED");
		mvc.perform(as(o.owner(), get("/api/organizations/" + o.orgId() + "/events/" + o.eventId() + "/notifications").param("status", "failed")))
				.andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(badId)).andExpect(jsonPath("$[0].lastError").exists());

		// un envío ya enviado no se reintenta; uno fallido sí
		mvc.perform(as(o.owner(), post("/api/organizations/" + o.orgId() + "/notifications/" + okId + "/retry"))).andExpect(status().isConflict());
		jdbc.sql("UPDATE notifications SET params = :p WHERE id = :id").param("p", "{\"name\":\"Arreglado\"}").param("id", badId).update();
		mvc.perform(as(o.owner(), post("/api/organizations/" + o.orgId() + "/notifications/" + badId + "/retry"))).andExpect(status().isAccepted());
		org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> "SENT".equals(row(badId).get("status")));
	}

	@Test
	void theSendLog_isForManagersOfTheOrganizationOnly() throws Exception {
		Org o = org();
		TestUser member = newUser();
		TestUser outsider = newUser();
		mvc.perform(as(o.owner(), post("/api/organizations/" + o.orgId() + "/invitations")).contentType("application/json")
				.content("{\"email\":\"%s\",\"role\":\"MEMBER\"}".formatted(member.email()))).andExpect(status().isCreated());
		mvc.perform(as(member, post("/api/invitations/accept")).contentType("application/json").content("{\"token\":\"%s\"}".formatted(lastTokenSentTo(member.email())))).andExpect(status().isOk());
		String id = queueFor(o, Map.of("mode", "fail"));
		dispatcher.dispatchDue(500);
		jdbc.sql("UPDATE notifications SET status = 'FAILED' WHERE id = :id").param("id", id).update();
		String url = "/api/organizations/" + o.orgId() + "/events/" + o.eventId() + "/notifications";

		mvc.perform(as(member, get(url))).andExpect(status().isForbidden());
		mvc.perform(as(member, post("/api/organizations/" + o.orgId() + "/notifications/" + id + "/retry"))).andExpect(status().isForbidden());
		mvc.perform(as(outsider, get(url))).andExpect(status().isNotFound());
		mvc.perform(as(outsider, post("/api/organizations/" + o.orgId() + "/notifications/" + id + "/retry"))).andExpect(status().isNotFound());
		mvc.perform(get(url)).andExpect(status().isUnauthorized());
		// otra organización no puede reactivar un envío ajeno usando su propio id de organización
		Org other = org();
		mvc.perform(as(other.owner(), post("/api/organizations/" + other.orgId() + "/notifications/" + id + "/retry"))).andExpect(status().isNotFound());
	}
}
