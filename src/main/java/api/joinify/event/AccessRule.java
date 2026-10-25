package api.joinify.event;

import java.util.Set;

/**
 * Regla de acceso de un tipo de entrada (ADR 0004).
 *
 * @param scope               todas las sesiones o solo las listadas
 * @param sessionIds          sesiones cubiertas; solo se usa con {@link Scope#SELECTED_SESSIONS}
 * @param maxTotalUses        máximo de ingresos en total; null = sin límite
 * @param maxUsesPerSession   máximo de ingresos por sesión; null = reingreso libre
 * @param earlyEntryMinutes   minutos antes del inicio desde los que se permite ingresar
 */
public record AccessRule(Scope scope, Set<String> sessionIds, Integer maxTotalUses, Integer maxUsesPerSession,
		int earlyEntryMinutes) {

	public enum Scope {
		ALL_SESSIONS, SELECTED_SESSIONS
	}

	public static final int DEFAULT_EARLY_ENTRY_MINUTES = 60;

	public AccessRule {
		sessionIds = sessionIds == null ? Set.of() : Set.copyOf(sessionIds);
	}

	public boolean covers(String sessionId) {
		return scope == Scope.ALL_SESSIONS || sessionIds.contains(sessionId);
	}
}
