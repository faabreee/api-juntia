package api.joinify.event;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import jakarta.validation.constraints.NotNull;

final class TicketTypeDtos {

	private TicketTypeDtos() {
	}

	enum Template {
		SINGLE, MULTI_DAY, N_USES, CUSTOM
	}

	enum SaleStatus {
		NOT_STARTED, ON_SALE, ENDED
	}

	record TicketTypeResponse(@NotNull String id, @NotNull String name, String description, @NotNull Template template,
			long priceMinor, @NotNull String currency, Integer quota, int maxPerOrder, Instant salesStart,
			Instant salesEnd, @NotNull AccessRule.Scope scope, @NotNull Set<String> sessionIds, Integer maxTotalUses,
			Integer maxUsesPerSession, int earlyEntryMinutes, boolean active, @NotNull SaleStatus saleStatus) {

		static TicketTypeResponse of(TicketTypeRepository.Row r, Set<String> sessionIds, Instant now) {
			Instant start = r.salesStart() == null ? null : r.salesStart().toInstant(ZoneOffset.UTC);
			Instant end = r.salesEnd() == null ? null : r.salesEnd().toInstant(ZoneOffset.UTC);
			SaleStatus status = start != null && now.isBefore(start) ? SaleStatus.NOT_STARTED
					: end != null && now.isAfter(end) ? SaleStatus.ENDED : SaleStatus.ON_SALE;
			return new TicketTypeResponse(r.id(), r.name(), r.description(), Template.valueOf(r.template()),
					r.priceMinor(), r.currency(), r.quota(), r.maxPerOrder(), start, end, r.scope(), sessionIds,
					r.maxTotalUses(), r.maxUsesPerSession(), r.earlyEntryMinutes(), r.active(), status);
		}
	}

	/** Entrada tal como la ve el público (solo tipos activos). */
	record PublicTicketTypeResponse(@NotNull String id, @NotNull String name, String description, long priceMinor,
			@NotNull String currency, int maxPerOrder, Instant salesStart, Instant salesEnd,
			@NotNull SaleStatus saleStatus, @NotNull AccessRule.Scope scope, @NotNull List<String> sessionIds,
			Integer maxTotalUses, Integer maxUsesPerSession) {

		static PublicTicketTypeResponse of(TicketTypeResponse t) {
			return new PublicTicketTypeResponse(t.id(), t.name(), t.description(), t.priceMinor(), t.currency(),
					t.maxPerOrder(), t.salesStart(), t.salesEnd(), t.saleStatus(), t.scope(),
					List.copyOf(t.sessionIds()), t.maxTotalUses(), t.maxUsesPerSession());
		}
	}
}
