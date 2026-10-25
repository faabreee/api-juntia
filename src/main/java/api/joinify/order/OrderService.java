package api.joinify.order;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import api.joinify.event.TicketInventory;
import api.joinify.event.TicketInventory.EventForSale;
import api.joinify.event.TicketInventory.TicketForSale;
import api.joinify.identity.AuthUser;
import api.joinify.order.OrderRepository.ItemRow;
import api.joinify.order.OrderRepository.OrderRow;
import api.joinify.organization.FeeSettings;
import api.joinify.organization.OrganizationAccess;

/**
 * Órdenes e inventario.
 *
 * Garantía central: nunca se vende más cupo del que existe. Cada reserva es un UPDATE condicional atómico dentro de
 * la transacción de la orden; si cualquier línea falla, toda la orden se revierte (sin cupo "fugado").
 */
@Service
public class OrderService {

	public record Line(String ticketTypeId, int quantity) {
	}

	public record OrderView(OrderRow order, List<ItemRow> items, boolean replayed) {
	}

	static final int MAX_LINES = 20;
	private static final Logger log = LoggerFactory.getLogger(OrderService.class);

	private final OrderRepository repository;
	private final TicketInventory inventory;
	private final OrganizationAccess organizations;
	private final ApplicationEventPublisher events;
	private final TransactionTemplate tx;
	private final Clock clock;
	private final Duration holdTime;

	OrderService(OrderRepository repository, TicketInventory inventory, OrganizationAccess organizations,
			ApplicationEventPublisher events, PlatformTransactionManager transactionManager, Clock clock, @Value("${app.orders.hold-minutes}") long holdMinutes) {
		this.repository = repository;
		this.inventory = inventory;
		this.organizations = organizations;
		this.events = events;
		this.tx = new TransactionTemplate(transactionManager);
		this.clock = clock;
		this.holdTime = Duration.ofMinutes(holdMinutes);
	}

	// ---------- crear ----------

	/**
	 * Crea la orden con reserva de cupo. Idempotente por (comprador, clave): repetir la misma petición devuelve la
	 * orden original; reutilizar la clave con otro contenido es un conflicto.
	 */
	public OrderView create(AuthUser buyer, String idempotencyKey, String eventSlug, List<Line> lines) {
		validateLines(lines);
		String hash = requestHash(eventSlug, lines);
		try {
			return tx.execute(status -> doCreate(buyer, idempotencyKey, eventSlug, lines, hash));
		} catch (DuplicateKeyException e) {
			// Otra petición con la misma clave ganó la carrera: se devuelve su orden.
			return replay(buyer, idempotencyKey, hash).orElseThrow(() -> e);
		}
	}

	private OrderView doCreate(AuthUser buyer, String key, String eventSlug, List<Line> lines, String hash) {
		var existing = replay(buyer, key, hash);
		if (existing.isPresent()) {
			return existing.get();
		}
		Instant now = clock.instant();
		EventForSale event = inventory.requireEventForSale(eventSlug, now);

		// Orden estable por tipo de entrada: evita interbloqueos entre órdenes concurrentes con varias líneas.
		List<Line> ordered = lines.stream().sorted(Comparator.comparing(Line::ticketTypeId)).toList();
		List<TicketForSale> tickets = ordered.stream()
				.map(l -> inventory.requireTicketForSale(event.id(), l.ticketTypeId(), l.quantity(), now)).toList();

		String currency = tickets.getFirst().currency();
		if (tickets.stream().anyMatch(t -> !t.currency().equals(currency))) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Todas las entradas de una orden deben tener la misma moneda");
		}
		long subtotal = 0;
		for (int i = 0; i < ordered.size(); i++) {
			subtotal += tickets.get(i).priceMinor() * ordered.get(i).quantity();
		}
		// Comisión de la plataforma (ADR 0008): la paga el comprador (se suma) o el organizador (se descuenta de su parte).
		FeeSettings fees = organizations.feeSettingsOf(event.organizationId());
		long fee = fees.feeFor(subtotal);
		long total = fees.mode() == FeeSettings.Mode.PASSED_TO_BUYER ? subtotal + fee : subtotal;

		// Una orden gratuita se completa sin pago; una de pago queda pendiente hasta que expire o se pague.
		boolean free = total == 0;
		LocalDateTime utcNow = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
		String orderId = repository.insert(event.id(), event.organizationId(), buyer.getId(),
				free ? OrderStatus.PAID : OrderStatus.PENDING, currency, subtotal, fee, fees.mode().name(), total, key,
				hash, utcNow, free ? null : utcNow.plus(holdTime));

		for (int i = 0; i < ordered.size(); i++) {
			TicketForSale ticket = tickets.get(i);
			inventory.reserve(ticket, ordered.get(i).quantity()); // 409 si no alcanza: revierte toda la orden
			repository.insertItem(orderId, ticket.id(), ticket.name(), ordered.get(i).quantity(), ticket.priceMinor());
		}
		if (free) {
			events.publishEvent(new OrderPaid(orderId)); // se emiten los boletos al confirmarse la transacción
		}
		return view(repository.findById(orderId).orElseThrow(), false);
	}

	private java.util.Optional<OrderView> replay(AuthUser buyer, String key, String hash) {
		return repository.findByBuyerAndKey(buyer.getId(), key).map(order -> {
			if (!order.requestHash().equals(hash)) {
				throw new ResponseStatusException(HttpStatus.CONFLICT,
						"La clave de idempotencia ya se usó con una petición distinta");
			}
			return view(order, true);
		});
	}

	// ---------- consultar y cancelar ----------

	public List<OrderView> mine(AuthUser buyer) {
		return repository.findByBuyer(buyer.getId(), 50).stream().map(o -> view(o, false)).toList();
	}

	public OrderView get(AuthUser buyer, String orderId) {
		return view(owned(buyer, orderId), false);
	}

	public OrderView cancel(AuthUser buyer, String orderId) {
		OrderRow order = owned(buyer, orderId);
		if (order.status() != OrderStatus.PENDING) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Solo se puede cancelar una orden pendiente");
		}
		if (!close(order.id(), OrderStatus.CANCELLED)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "La orden ya no está pendiente");
		}
		return view(repository.findById(orderId).orElseThrow(), false);
	}

	// ---------- ciclo de vida interno (lo usan el barrido de expiración y la etapa de pagos) ----------

	/** Expira las órdenes pendientes vencidas y devuelve su cupo. Seguro con varias instancias a la vez. */
	public int expireDue() {
		LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
		int closed = 0;
		for (String id : repository.findDuePendingIds(now, 200)) {
			if (close(id, OrderStatus.EXPIRED)) {
				closed++;
			}
		}
		return closed;
	}

	/** Marca la orden como pagada si sigue pendiente. La etapa de pagos decide qué hacer si devuelve false. */
	public boolean confirmPayment(String orderId) {
		return Boolean.TRUE.equals(tx.execute(status -> {
			if (!repository.markPaid(orderId, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))) {
				return false;
			}
			events.publishEvent(new OrderPaid(orderId));
			return true;
		}));
	}

	/** Datos de una orden para cobrarla. Una orden ajena se comporta como inexistente. */
	public record PayableOrder(String id, String organizationId, OrderStatus status, java.time.Instant expiresAt,
			long totalMinor, long subtotalMinor, long feeMinor, String currency, String eventTitle) {
	}

	public PayableOrder payableOf(AuthUser buyer, String orderId) {
		return toPayable(owned(buyer, orderId));
	}

	/** Igual que {@link #payableOf} pero sin comprobar el comprador: para webhooks y conciliación. */
	public PayableOrder payableOf(String orderId) {
		return toPayable(repository.findById(orderId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Orden no encontrada")));
	}

	private static PayableOrder toPayable(OrderRow o) {
		return new PayableOrder(o.id(), o.organizationId(), o.status(),
				o.expiresAt() == null ? null : o.expiresAt().toInstant(ZoneOffset.UTC), o.totalMinor(), o.subtotalMinor(),
				o.feeMinor(), o.currency(), o.eventTitle());
	}

	/**
	 * Pago tardío: la orden ya expiró o se canceló pero el dinero llegó. Si todavía hay cupo para todas las líneas se
	 * vuelve a reservar y la orden queda pagada; si no, no se toca nada y devuelve false (el pago se reembolsa).
	 */
	public boolean reviveWithPayment(String orderId) {
		return Boolean.TRUE.equals(tx.execute(status -> {
			OrderRow order = repository.findById(orderId).orElse(null);
			if (order == null || (order.status() != OrderStatus.EXPIRED && order.status() != OrderStatus.CANCELLED)) {
				return false;
			}
			// Se compensa a mano en lugar de marcar la transacción como "solo revertir": quien llama puede
			// estar dentro de su propia transacción (la del pago) y no debe perderla por esto.
			List<ItemRow> reserved = new java.util.ArrayList<>();
			for (ItemRow item : repository.findItems(orderId)) {
				if (!inventory.tryReserve(item.ticketTypeId(), item.quantity())) {
					reserved.forEach(r -> inventory.release(r.ticketTypeId(), r.quantity()));
					return false;
				}
				reserved.add(item);
			}
			if (!repository.reopenAsPaid(orderId, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))) {
				reserved.forEach(r -> inventory.release(r.ticketTypeId(), r.quantity()));
				return false;
			}
			events.publishEvent(new OrderPaid(orderId));
			return true;
		}));
	}

	/** Reembolso total: la orden queda REFUNDED y sus entradas vuelven al inventario. */
	public boolean markRefunded(String orderId) {
		return Boolean.TRUE.equals(tx.execute(status -> {
			if (!repository.markRefunded(orderId, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))) {
				return false;
			}
			for (ItemRow item : repository.findItems(orderId)) {
				inventory.release(item.ticketTypeId(), item.quantity());
			}
			events.publishEvent(new OrderRefunded(orderId));
			return true;
		}));
	}

	/** Líneas de una orden pagada, para emitir sus boletos. Vacío si la orden no está pagada. */
	public record IssuanceData(String orderId, String eventId, String buyerId, List<Line> lines) {
		public record Line(String orderItemId, String ticketTypeId, int quantity) {
		}
	}

	public java.util.Optional<IssuanceData> issuanceDataOf(String orderId) {
		return repository.findById(orderId).filter(o -> o.status() == OrderStatus.PAID).map(o -> new IssuanceData(o.id(), o.eventId(),
				o.buyerId(), repository.findItemsWithId(o.id()).stream()
						.map(i -> new IssuanceData.Line(i.id(), i.ticketTypeId(), i.quantity())).toList()));
	}

	/** El comprador de una orden (para comprobar que un boleto es de quien lo pide). */
	public java.util.Optional<String> buyerOf(String orderId) {
		return repository.findById(orderId).map(OrderRow::buyerId);
	}

	/** Órdenes de una organización, para su panel de ventas (el llamador ya comprobó el permiso). */
	public List<OrderView> forOrganization(String organizationId) {
		return repository.findByOrganization(organizationId, 200).stream().map(o -> view(o, false)).toList();
	}

	/** Cierra la orden y libera su cupo en una sola transacción; solo libera quien efectivamente la cerró. */
	private boolean close(String orderId, OrderStatus to) {
		return Boolean.TRUE.equals(tx.execute(status -> {
			if (!repository.closePending(orderId, to, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))) {
				return false;
			}
			for (ItemRow item : repository.findItems(orderId)) {
				inventory.release(item.ticketTypeId(), item.quantity());
			}
			log.info("Orden {} {}: cupo liberado", orderId, to);
			return true;
		}));
	}

	// ---------- utilidades ----------

	private OrderRow owned(AuthUser buyer, String orderId) {
		// Una orden ajena se comporta como inexistente.
		return repository.findById(orderId).filter(o -> o.buyerId().equals(buyer.getId()))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Orden no encontrada"));
	}

	private OrderView view(OrderRow order, boolean replayed) {
		return new OrderView(order, repository.findItems(order.id()), replayed);
	}

	private static void validateLines(List<Line> lines) {
		if (lines == null || lines.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La orden debe tener al menos una entrada");
		}
		if (lines.size() > MAX_LINES) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Demasiadas líneas en la orden");
		}
		Set<String> seen = new HashSet<>();
		for (Line line : lines) {
			if (line.quantity() < 1) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La cantidad debe ser al menos 1");
			}
			if (!seen.add(line.ticketTypeId())) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cada tipo de entrada debe aparecer una sola vez");
			}
		}
	}

	/** Huella del contenido, independiente del orden de las líneas. */
	private static String requestHash(String eventSlug, List<Line> lines) {
		StringBuilder canonical = new StringBuilder(eventSlug);
		lines.stream().sorted(Comparator.comparing(Line::ticketTypeId))
				.forEach(l -> canonical.append('|').append(l.ticketTypeId()).append('x').append(l.quantity()));
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
