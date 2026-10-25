package api.joinify.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
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

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.MailSendException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.jayway.jsonpath.JsonPath;

import api.joinify.IntegrationTestSupport;
import api.joinify.order.OrderService;
import api.joinify.payment.SimulatedGateway;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;

@TestPropertySource(properties = { "app.storage.dir=build/test-uploads", "app.fees.bps=0", "app.payments.reconcile-interval-ms=3600000",
		"app.tickets.repair-interval-ms=3600000" })
class TicketTests extends IntegrationTestSupport {

	@Autowired
	TicketIssuer issuer;

	@Autowired
	TicketRepairJob repairJob;

	@Autowired
	TicketTokenService tokens;

	@Autowired
	SimulatedGateway gateway;

	@Autowired
	OrderService orderService;

	@Autowired
	JdbcClient jdbc;

	private record Shop(TestUser owner, String orgId, String eventId, String slug, String typeId, List<String> sessionIds) {
		String events() {
			return "/api/organizations/" + orgId + "/events/" + eventId;
		}
	}

	private Shop shop(long priceMinor, boolean nominative) throws Exception {
		return shop(priceMinor, nominative, 20);
	}

	/** Evento publicado con dos sesiones y una entrada para todas ellas. */
	private Shop shop(long priceMinor, boolean nominative, int quota) throws Exception {
		TestUser owner = newUser();
		String org = JsonPath.read(mvc.perform(as(owner, post("/api/organizations")).contentType("application/json")
				.content("{\"name\":\"Productora %s\",\"type\":\"COMPANY\",\"country\":\"PE\",\"currency\":\"PEN\",\"timezone\":\"America/Lima\"}"
						.formatted(Math.random()))).andReturn().getResponse().getContentAsString(), "$.id");
		String event = JsonPath.read(mvc.perform(as(owner, post("/api/organizations/" + org + "/events")).contentType("application/json")
				.content("{\"title\":\"Festival de boletos\",\"timezone\":\"America/Lima\"}")).andReturn().getResponse().getContentAsString(), "$.id");
		List<String> sessions = new ArrayList<>();
		Instant day1 = Instant.now().plus(Duration.ofDays(20));
		for (int i = 0; i < 2; i++) {
			Instant start = day1.plus(Duration.ofDays(i));
			sessions.add(JsonPath.read(mvc.perform(as(owner, post("/api/organizations/" + org + "/events/" + event + "/sessions")).contentType("application/json")
					.content("{\"name\":\"Día %d\",\"startsAt\":\"%s\",\"endsAt\":\"%s\"}".formatted(i + 1, start, start.plus(Duration.ofHours(6)))))
					.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id"));
		}
		String type = JsonPath.read(mvc.perform(as(owner, post("/api/organizations/" + org + "/events/" + event + "/ticket-types")).contentType("application/json")
				.content("{\"name\":\"Pase general\",\"template\":\"MULTI_DAY\",\"priceMinor\":%d,\"quota\":%d,\"maxPerOrder\":10,\"scope\":\"ALL_SESSIONS\",\"maxUsesPerSession\":1,\"nominative\":%s}"
						.formatted(priceMinor, quota, nominative))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
		mvc.perform(as(owner, post("/api/organizations/" + org + "/events/" + event + "/publish"))).andExpect(status().isOk());
		String slug = JsonPath.read(mvc.perform(as(owner, get("/api/organizations/" + org + "/events/" + event))).andReturn().getResponse().getContentAsString(), "$.slug");
		return new Shop(owner, org, event, slug, type, sessions);
	}

	private String orderOf(Shop shop, TestUser buyer, int quantity) throws Exception {
		return JsonPath.read(mvc.perform(as(buyer, post("/api/orders")).header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
				.content("{\"eventSlug\":\"%s\",\"items\":[{\"ticketTypeId\":\"%s\",\"quantity\":%d}]}".formatted(shop.slug(), shop.typeId(), quantity)))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
	}

	private ResultActions pay(TestUser buyer, String orderId, String token) throws Exception {
		return mvc.perform(as(buyer, post("/api/orders/" + orderId + "/payments")).header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType("application/json").content("{\"sourceToken\":\"%s\"}".formatted(token)));
	}

	/** Compra y paga con éxito; devuelve el id de la orden. */
	private String paidOrder(Shop shop, TestUser buyer, int quantity) throws Exception {
		String order = orderOf(shop, buyer, quantity);
		pay(buyer, order, "tok_success").andExpect(status().isCreated());
		return order;
	}

	private long ticketCount(String orderId) {
		return jdbc.sql("SELECT COUNT(*) FROM tickets WHERE order_id = :id").param("id", orderId).query(Long.class).single();
	}

	private List<String> ticketIds(TestUser owner) throws Exception {
		String body = mvc.perform(as(owner, get("/api/tickets"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$[*].id");
	}

	private String json(TestUser user, String path) throws Exception {
		return mvc.perform(as(user, get(path))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	private void refund(Shop shop, String orderId, String body) throws Exception {
		mvc.perform(as(shop.owner(), post("/api/organizations/" + shop.orgId() + "/orders/" + orderId + "/refunds"))
				.header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json").content(body)).andExpect(status().isCreated());
	}

	// ---------- criterio de terminado: compras y en segundos tienes tu boleto con QR ----------

	@Test
	void paidOrder_issuesOneSignedTicketPerSeat() throws Exception {
		Shop shop = shop(8_000, false);
		TestUser buyer = newUser();
		String order = paidOrder(shop, buyer, 3);

		String body = json(buyer, "/api/tickets");
		assertThat(JsonPath.<List<String>>read(body, "$[*].id")).hasSize(3);
		List<String> codes = JsonPath.read(body, "$[*].code");
		assertThat(codes).doesNotHaveDuplicates().allMatch(c -> c.matches("[2-9A-HJKMNP-TV-Z]{4}-[2-9A-HJKMNP-TV-Z]{4}"));
		assertThat(JsonPath.<List<String>>read(body, "$[*].status")).containsOnly("VALID");
		assertThat(JsonPath.<List<String>>read(body, "$[*].orderId")).containsOnly(order);
		assertThat(JsonPath.<List<String>>read(body, "$[*].eventTitle")).containsOnly("Festival de boletos");

		// cada QR es un token firmado que identifica a su boleto y a su evento
		List<String> ids = JsonPath.read(body, "$[*].id");
		List<String> qrs = JsonPath.read(body, "$[*].qrToken");
		for (int i = 0; i < ids.size(); i++) {
			var verified = tokens.verify(qrs.get(i));
			assertThat(verified).isPresent();
			assertThat(verified.get().ticketId()).isEqualTo(ids.get(i));
			assertThat(verified.get().eventId()).isEqualTo(shop.eventId());
		}
		assertThat(qrs).doesNotHaveDuplicates();
		assertThat(JsonPath.<List<String>>read(json(buyer, "/api/orders/" + order + "/tickets"), "$[*].id")).containsExactlyInAnyOrderElementsOf(ids);
	}

	@Test
	void ticketsAreIssuedOnlyOncePaid() throws Exception {
		Shop shop = shop(8_000, false);
		TestUser buyer = newUser();
		String pending = orderOf(shop, buyer, 2);
		String declined = orderOf(shop, buyer, 1);
		pay(buyer, declined, "tok_declined").andExpect(jsonPath("$.status").value("FAILED"));

		assertThat(ticketCount(pending)).isZero();
		assertThat(ticketCount(declined)).isZero();
		assertThat(ticketIds(buyer)).isEmpty();
		assertThat(issuer.issue(pending)).as("una orden pendiente no emite nada").isZero();
	}

	@Test
	void freeOrder_issuesTicketsRightAway() throws Exception {
		Shop shop = shop(0, false);
		TestUser buyer = newUser();
		String order = orderOf(shop, buyer, 2);

		assertThat(ticketCount(order)).isEqualTo(2);
		assertThat(ticketIds(buyer)).hasSize(2);
	}

	@Test
	void issuingTwice_neverDuplicates() throws Exception {
		Shop shop = shop(8_000, false);
		TestUser buyer = newUser();
		String order = paidOrder(shop, buyer, 3);

		assertThat(issuer.issue(order)).isZero();
		assertThat(issuer.issue(order)).isZero();
		assertThat(ticketCount(order)).isEqualTo(3);
	}

	@Test
	void concurrentIssuance_createsExactlyOneTicketPerSeat() throws Exception {
		Shop shop = shop(8_000, false);
		TestUser buyer = newUser();
		String order = paidOrder(shop, buyer, 4);
		jdbc.sql("DELETE FROM tickets WHERE order_id = :id").param("id", order).update(); // como si la emisión no hubiera ocurrido

		ExecutorService pool = Executors.newFixedThreadPool(8);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<Integer>> results = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			results.add(pool.submit(() -> {
				go.await();
				return issuer.issue(order);
			}));
		}
		go.countDown();
		int created = 0;
		for (Future<Integer> r : results) {
			created += r.get();
		}
		pool.shutdown();

		assertThat(ticketCount(order)).isEqualTo(4);
		assertThat(created).as("entre todos los emisores crearon cada boleto una sola vez").isEqualTo(4);
		assertThat(jdbc.sql("SELECT COUNT(DISTINCT code) FROM tickets WHERE order_id = :id").param("id", order).query(Long.class).single()).isEqualTo(4);
	}

	@Test
	void repairJob_completesAnInterruptedIssuance() throws Exception {
		Shop shop = shop(8_000, false);
		TestUser buyer = newUser();
		String order = paidOrder(shop, buyer, 3);
		// la emisión se interrumpió a medias: falta un boleto; y la orden se pagó hace más de 30 s
		jdbc.sql("DELETE FROM tickets WHERE order_id = :id AND seq = 2").param("id", order).update();
		jdbc.sql("UPDATE orders SET paid_at = :t WHERE id = :id").param("t", LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5)).param("id", order).update();
		assertThat(ticketCount(order)).isEqualTo(2);

		assertThat(repairJob.repair()).isGreaterThanOrEqualTo(1);

		assertThat(ticketCount(order)).isEqualTo(3);
		assertThat(repairJob.repair()).as("repetir no cambia nada").isZero();
		assertThat(ticketIds(buyer)).hasSize(3);
	}

	@Test
	void repairJob_leavesRecentOrdersToTheNormalFlow() throws Exception {
		Shop shop = shop(8_000, false);
		TestUser buyer = newUser();
		String order = paidOrder(shop, buyer, 2);
		jdbc.sql("DELETE FROM tickets WHERE order_id = :id").param("id", order).update();

		repairJob.repair(); // recién pagada: dentro del plazo de gracia

		assertThat(ticketCount(order)).isZero();
	}

	// ---------- anulación por reembolso ----------

	@Test
	void fullRefund_voidsTheTickets_partialRefundDoesNot() throws Exception {
		Shop shop = shop(8_000, false);
		TestUser buyer = newUser();
		String partial = paidOrder(shop, buyer, 2);
		String full = paidOrder(shop, buyer, 2);

		refund(shop, partial, "{\"amountMinor\":1000,\"reason\":\"OTHER\"}");
		assertThat(jdbc.sql("SELECT COUNT(*) FROM tickets WHERE order_id = :id AND status = 'VALID'").param("id", partial).query(Long.class).single()).isEqualTo(2);

		refund(shop, full, "{\"reason\":\"REQUESTED_BY_BUYER\"}");
		assertThat(jdbc.sql("SELECT COUNT(*) FROM tickets WHERE order_id = :id AND status = 'VOID'").param("id", full).query(Long.class).single()).isEqualTo(2);

		String body = json(buyer, "/api/orders/" + full + "/tickets");
		assertThat(JsonPath.<List<String>>read(body, "$[*].status")).containsOnly("VOID");
		assertThat(JsonPath.<List<Object>>read(body, "$[*].qrToken")).as("un boleto anulado ya no entrega QR").containsOnlyNulls();
		String voidId = JsonPath.read(body, "$[0].id");
		mvc.perform(as(buyer, get("/api/tickets/" + voidId + "/pdf"))).andExpect(status().isConflict());
		mvc.perform(as(buyer, get("/api/orders/" + full + "/tickets/pdf"))).andExpect(status().isNotFound());
	}

	@Test
	void repairJob_voidsTicketsOfRefundedOrdersThatWereMissed() throws Exception {
		Shop shop = shop(8_000, false);
		TestUser buyer = newUser();
		String order = paidOrder(shop, buyer, 2);
		refund(shop, order, "{\"reason\":\"OTHER\"}");
		jdbc.sql("UPDATE tickets SET status = 'VALID', voided_at = NULL, void_reason = NULL WHERE order_id = :id").param("id", order).update();

		repairJob.repair();

		assertThat(jdbc.sql("SELECT COUNT(*) FROM tickets WHERE order_id = :id AND status = 'VOID'").param("id", order).query(Long.class).single()).isEqualTo(2);
	}

	@Test
	void latePayment_thatReopensTheOrder_issuesItsTickets() throws Exception {
		Shop shop = shop(8_000, false);
		TestUser buyer = newUser();
		String order = orderOf(shop, buyer, 1);
		String payment = JsonPath.read(pay(buyer, order, "tok_pending").andReturn().getResponse().getContentAsString(), "$.id");
		jdbc.sql("UPDATE orders SET expires_at = :t WHERE id = :id").param("t", LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1)).param("id", order).update();
		orderService.expireDue();
		assertThat(ticketCount(order)).isZero();

		String charge = jdbc.sql("SELECT provider_payment_id FROM payments WHERE id = :id").param("id", payment).query(String.class).single();
		gateway.settle(charge, api.joinify.payment.PaymentGateway.Status.SUCCEEDED);
		String webhook = "{\"id\":\"evt_tk_%s\",\"type\":\"charge.succeeded\",\"chargeId\":\"%s\"}".formatted(UUID.randomUUID(), charge);
		mvc.perform(post("/api/webhooks/simulated").header("X-Signature", gateway.sign(webhook)).contentType("application/json").content(webhook))
				.andExpect(status().isOk());

		assertThat(ticketCount(order)).isEqualTo(1);
	}

	// ---------- privacidad ----------

	@Test
	void ticketsArePrivateToTheirOwner() throws Exception {
		Shop shop = shop(8_000, true);
		TestUser buyer = newUser();
		TestUser stranger = newUser();
		String order = paidOrder(shop, buyer, 1);
		String ticket = ticketIds(buyer).getFirst();

		mvc.perform(as(stranger, get("/api/tickets/" + ticket))).andExpect(status().isNotFound());
		mvc.perform(as(stranger, get("/api/tickets/" + ticket + "/pdf"))).andExpect(status().isNotFound());
		mvc.perform(as(stranger, put("/api/tickets/" + ticket + "/attendee")).contentType("application/json").content("{\"name\":\"Intruso\"}")).andExpect(status().isNotFound());
		mvc.perform(as(stranger, get("/api/orders/" + order + "/tickets"))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
		mvc.perform(as(stranger, get("/api/orders/" + order + "/tickets/pdf"))).andExpect(status().isNotFound());
		assertThat(ticketIds(stranger)).isEmpty();
		mvc.perform(get("/api/tickets")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/tickets/" + ticket)).andExpect(status().isUnauthorized());
		// ni el organizador ve los boletos por esta vía
		mvc.perform(as(shop.owner(), get("/api/tickets/" + ticket))).andExpect(status().isNotFound());
	}

	// ---------- nominativos y detalle ----------

	@Test
	void nominativeTicket_acceptsTheAttendeeName() throws Exception {
		Shop shop = shop(8_000, true);
		TestUser buyer = newUser();
		paidOrder(shop, buyer, 2);
		List<String> ids = ticketIds(buyer);

		mvc.perform(as(buyer, get("/api/tickets/" + ids.get(0)))).andExpect(jsonPath("$.ticket.nominative").value(true)).andExpect(jsonPath("$.ticket.attendeeName").doesNotExist());
		mvc.perform(as(buyer, put("/api/tickets/" + ids.get(0) + "/attendee")).contentType("application/json").content("{\"name\":\"  Ana   María   Pérez \"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.attendeeName").value("Ana María Pérez"));
		mvc.perform(as(buyer, put("/api/tickets/" + ids.get(1) + "/attendee")).contentType("application/json").content("{\"name\":\"X\"}")).andExpect(status().isBadRequest());
		mvc.perform(as(buyer, put("/api/tickets/" + ids.get(1) + "/attendee")).contentType("application/json").content("{\"name\":\"" + "a".repeat(121) + "\"}")).andExpect(status().isBadRequest());
		mvc.perform(as(buyer, put("/api/tickets/" + ids.get(1) + "/attendee")).contentType("application/json").content("{}")).andExpect(status().isBadRequest());
		mvc.perform(as(buyer, get("/api/tickets/" + ids.get(1)))).andExpect(jsonPath("$.ticket.attendeeName").doesNotExist());
	}

	@Test
	void nonNominativeAndVoidTickets_rejectAttendeeNames() throws Exception {
		Shop plain = shop(8_000, false);
		TestUser buyer = newUser();
		paidOrder(plain, buyer, 1);
		String plainTicket = ticketIds(buyer).getFirst();
		mvc.perform(as(buyer, put("/api/tickets/" + plainTicket + "/attendee")).contentType("application/json").content("{\"name\":\"Ana Pérez\"}")).andExpect(status().isConflict());

		Shop named = shop(8_000, true);
		TestUser other = newUser();
		String order = paidOrder(named, other, 1);
		String ticket = ticketIds(other).getFirst();
		refund(named, order, "{\"reason\":\"OTHER\"}");
		mvc.perform(as(other, put("/api/tickets/" + ticket + "/attendee")).contentType("application/json").content("{\"name\":\"Ana Pérez\"}")).andExpect(status().isConflict());
	}

	@Test
	void ticketDetail_describesWhatItCovers() throws Exception {
		Shop shop = shop(8_000, false);
		TestUser buyer = newUser();
		paidOrder(shop, buyer, 1);
		String ticket = ticketIds(buyer).getFirst();

		mvc.perform(as(buyer, get("/api/tickets/" + ticket))).andExpect(status().isOk()).andExpect(jsonPath("$.sessions.length()").value(2))
				.andExpect(jsonPath("$.sessions[0].name").value("Día 1")).andExpect(jsonPath("$.maxUsesPerSession").value(1))
				.andExpect(jsonPath("$.maxTotalUses").doesNotExist()).andExpect(jsonPath("$.earlyEntryMinutes").value(60))
				.andExpect(jsonPath("$.organizationName").exists()).andExpect(jsonPath("$.ticket.timezone").value("America/Lima"));
	}

	// ---------- PDF ----------

	@Test
	void pdf_hasOnePagePerTicket_andItsQrScansToTheSignedToken() throws Exception {
		Shop shop = shop(8_000, false);
		TestUser buyer = newUser();
		String order = paidOrder(shop, buyer, 2);
		String body = json(buyer, "/api/orders/" + order + "/tickets");
		List<String> ids = JsonPath.read(body, "$[*].id");
		List<String> codes = JsonPath.read(body, "$[*].code");

		byte[] bytes = mvc.perform(as(buyer, get("/api/orders/" + order + "/tickets/pdf"))).andExpect(status().isOk())
				.andExpect(content().contentType("application/pdf")).andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
				.andReturn().getResponse().getContentAsByteArray();

		assertThat(new String(bytes, 0, 5)).isEqualTo("%PDF-");
		try (PDDocument document = Loader.loadPDF(bytes)) {
			assertThat(document.getNumberOfPages()).isEqualTo(2);
			String text = new PDFTextStripper().getText(document);
			assertThat(text).contains("Festival de boletos").contains("Pase general").contains("Día 1").contains("Día 2");
			codes.forEach(c -> assertThat(text).contains(c));

			// el QR dibujado en la página se lee con un escáner y es el token firmado del boleto
			BufferedImage page = new PDFRenderer(document).renderImageWithDPI(0, 150);
			var result = new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(page))));
			var verified = tokens.verify(result.getText());
			assertThat(verified).isPresent();
			assertThat(ids).contains(verified.get().ticketId());
		}
		mvc.perform(as(buyer, get("/api/tickets/" + ids.getFirst() + "/pdf"))).andExpect(status().isOk()).andExpect(content().contentType("application/pdf"));
	}

	@Test
	void pdf_survivesCharactersTheFontCannotDraw() throws Exception {
		Shop shop = shop(8_000, true);
		TestUser buyer = newUser();
		paidOrder(shop, buyer, 1);
		String ticket = ticketIds(buyer).getFirst();
		mvc.perform(as(buyer, put("/api/tickets/" + ticket + "/attendee")).contentType("application/json").content("{\"name\":\"Zoë 王小明 🎫\"}")).andExpect(status().isOk());

		byte[] bytes = mvc.perform(as(buyer, get("/api/tickets/" + ticket + "/pdf"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();

		try (PDDocument document = Loader.loadPDF(bytes)) {
			assertThat(new PDFTextStripper().getText(document)).contains("Zoë");
		}
	}

	// ---------- correo ----------

	private MimeMessage sentTo(String email) {
		return org.mockito.Mockito.mockingDetails(mailSender).getInvocations().stream()
				.filter(i -> i.getMethod().getName().equals("send") && i.getArguments().length == 1 && i.getArguments()[0] instanceof MimeMessage)
				.map(i -> (MimeMessage) i.getArguments()[0]).filter(m -> {
					try {
						return m.getAllRecipients() != null && m.getAllRecipients()[0].toString().equals(email);
					} catch (jakarta.mail.MessagingException e) {
						return false;
					}
				}).findFirst().orElse(null);
	}

	@Test
	void buyerReceivesTheTicketsByEmail_withThePdfAttached() throws Exception {
		Shop shop = shop(8_000, false);
		TestUser buyer = newUser();
		paidOrder(shop, buyer, 2);

		// El envío es asíncrono y el simulado puede traer correos tardíos de otras pruebas: se busca el de este comprador.
		MimeMessage message = org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> sentTo(buyer.email()), java.util.Objects::nonNull);

		assertThat(message.getAllRecipients()[0].toString()).isEqualTo(buyer.email());
		assertThat(message.getSubject()).isEqualTo("Tus boletos para Festival de boletos");
		Multipart multipart = (Multipart) message.getContent();
		boolean hasPdf = false;
		String text = "";
		for (int i = 0; i < multipart.getCount(); i++) {
			var part = multipart.getBodyPart(i);
			if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) && part.getFileName().endsWith(".pdf")) {
				hasPdf = true;
				try (PDDocument document = Loader.loadPDF(part.getInputStream().readAllBytes())) {
					assertThat(document.getNumberOfPages()).isEqualTo(2);
				}
			} else if (part.getContent() instanceof String s) {
				text += s;
			} else if (part.getContent() instanceof Multipart nested) {
				for (int j = 0; j < nested.getCount(); j++) {
					if (nested.getBodyPart(j).getContent() instanceof String s) {
						text += s;
					}
				}
			}
		}
		assertThat(hasPdf).isTrue();
		assertThat(text).contains("2 boletos").contains("/tickets");
	}

	@Test
	void aFailingMailServer_doesNotAffectThePurchase() throws Exception {
		doThrow(new MailSendException("SMTP caído")).when(mailSender).send(any(MimeMessage.class));
		Shop shop = shop(8_000, false);
		TestUser buyer = newUser();

		String order = paidOrder(shop, buyer, 2);

		assertThat(ticketCount(order)).isEqualTo(2);
		assertThat(ticketIds(buyer)).hasSize(2);
		mvc.perform(as(buyer, get("/api/orders/" + order))).andExpect(jsonPath("$.status").value("PAID"));
	}
}
