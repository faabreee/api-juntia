package api.joinify.event;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Decide si un boleto puede ingresar a una sesión (ADR 0004). Clase pura: sin base de datos, sin reloj propio y
 * sin estado; el llamador aporta la regla, el historial de ingresos aceptados y la hora actual.
 */
public final class AccessPolicy {

	public enum DenyReason {
		SESSION_NOT_INCLUDED, TOO_EARLY, SESSION_ENDED, TOTAL_USES_EXHAUSTED, ALREADY_USED_IN_SESSION
	}

	/** Sesión a la que se intenta ingresar. */
	public record SessionSlot(String id, Instant startsAt, Instant endsAt) {
	}

	/** Ingreso ya aceptado. */
	public record Entry(String sessionId, Instant at) {
	}

	/** Resultado: permitido, o el motivo del rechazo. */
	public record Decision(boolean allowed, DenyReason reason) {
		static final Decision ALLOWED = new Decision(true, null);

		static Decision denied(DenyReason reason) {
			return new Decision(false, reason);
		}
	}

	private static final int MAX_EARLY_ENTRY_MINUTES = 24 * 60;

	private AccessPolicy() {
	}

	/** Las comprobaciones se aplican en el orden del ADR 0004; la primera que falla decide. */
	public static Decision evaluate(AccessRule rule, SessionSlot session, List<Entry> history, Instant now) {
		if (!rule.covers(session.id())) {
			return Decision.denied(DenyReason.SESSION_NOT_INCLUDED);
		}
		if (now.isBefore(session.startsAt().minus(Duration.ofMinutes(rule.earlyEntryMinutes())))) {
			return Decision.denied(DenyReason.TOO_EARLY);
		}
		if (now.isAfter(session.endsAt())) {
			return Decision.denied(DenyReason.SESSION_ENDED);
		}
		if (rule.maxTotalUses() != null && history.size() >= rule.maxTotalUses()) {
			return Decision.denied(DenyReason.TOTAL_USES_EXHAUSTED);
		}
		if (rule.maxUsesPerSession() != null) {
			long usedHere = history.stream().filter(e -> e.sessionId().equals(session.id())).count();
			if (usedHere >= rule.maxUsesPerSession()) {
				return Decision.denied(DenyReason.ALREADY_USED_IN_SESSION);
			}
		}
		return Decision.ALLOWED;
	}

	/** Problemas de una regla al configurarla (lista vacía = válida). {@code eventSessionIds} son las sesiones del evento. */
	public static List<String> validate(AccessRule rule, Set<String> eventSessionIds) {
		List<String> problems = new ArrayList<>();
		if (rule.scope() == AccessRule.Scope.SELECTED_SESSIONS) {
			if (rule.sessionIds().isEmpty()) {
				problems.add("Elige al menos una sesión");
			} else if (!eventSessionIds.containsAll(rule.sessionIds())) {
				problems.add("Hay sesiones que no pertenecen al evento");
			}
		} else if (!rule.sessionIds().isEmpty()) {
			problems.add("Con todas las sesiones no se listan sesiones");
		}
		if (rule.maxTotalUses() != null && rule.maxTotalUses() < 1) {
			problems.add("El máximo de ingresos debe ser al menos 1");
		}
		if (rule.maxUsesPerSession() != null && rule.maxUsesPerSession() < 1) {
			problems.add("El máximo de ingresos por sesión debe ser al menos 1");
		}
		if (rule.maxTotalUses() != null && rule.maxUsesPerSession() != null && rule.maxUsesPerSession() > rule.maxTotalUses()) {
			problems.add("Los ingresos por sesión no pueden superar el máximo total");
		}
		if (rule.earlyEntryMinutes() < 0 || rule.earlyEntryMinutes() > MAX_EARLY_ENTRY_MINUTES) {
			problems.add("La anticipación de ingreso debe estar entre 0 y 1440 minutos");
		}
		return problems;
	}
}
