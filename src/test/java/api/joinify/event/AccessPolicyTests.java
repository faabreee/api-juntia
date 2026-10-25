package api.joinify.event;

import static api.joinify.event.AccessPolicy.DenyReason.ALREADY_USED_IN_SESSION;
import static api.joinify.event.AccessPolicy.DenyReason.SESSION_ENDED;
import static api.joinify.event.AccessPolicy.DenyReason.SESSION_NOT_INCLUDED;
import static api.joinify.event.AccessPolicy.DenyReason.TOO_EARLY;
import static api.joinify.event.AccessPolicy.DenyReason.TOTAL_USES_EXHAUSTED;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import api.joinify.event.AccessPolicy.Decision;
import api.joinify.event.AccessPolicy.DenyReason;
import api.joinify.event.AccessPolicy.Entry;
import api.joinify.event.AccessPolicy.SessionSlot;
import api.joinify.event.AccessRule.Scope;

/** Todos los ejemplos del ADR 0004 y sus bordes. */
class AccessPolicyTests {

	private static final Instant DAY1 = Instant.parse("2027-03-05T18:00:00Z");

	/** Sesión de 6 horas, un día después de la anterior. */
	private static SessionSlot session(String id, int dayOffset) {
		Instant start = DAY1.plus(Duration.ofDays(dayOffset));
		return new SessionSlot(id, start, start.plus(Duration.ofHours(6)));
	}

	private static final SessionSlot S1 = session("S1", 0);
	private static final SessionSlot S2 = session("S2", 1);
	private static final SessionSlot S3 = session("S3", 2);
	private static final SessionSlot S4 = session("S4", 3);
	private static final SessionSlot S5 = session("S5", 4);

	private static Instant during(SessionSlot s) {
		return s.startsAt().plus(Duration.ofHours(1));
	}

	/** Simula la puerta: evalúa y, si se permite, registra el ingreso. */
	private static class Gate {
		final AccessRule rule;
		final List<Entry> history = new ArrayList<>();

		Gate(AccessRule rule) {
			this.rule = rule;
		}

		Decision scan(SessionSlot session) {
			return scan(session, during(session));
		}

		Decision scan(SessionSlot session, Instant now) {
			Decision d = AccessPolicy.evaluate(rule, session, history, now);
			if (d.allowed()) {
				history.add(new Entry(session.id(), now));
			}
			return d;
		}
	}

	private static void assertAllowed(Decision d) {
		assertThat(d.allowed()).as("permitido").isTrue();
		assertThat(d.reason()).isNull();
	}

	private static void assertDenied(Decision d, DenyReason reason) {
		assertThat(d.allowed()).as("rechazado").isFalse();
		assertThat(d.reason()).isEqualTo(reason);
	}

	private static AccessRule rule(Scope scope, Set<String> sessions, Integer total, Integer perSession) {
		return new AccessRule(scope, sessions, total, perSession, 60);
	}

	// ---------- ejemplos del ADR ----------

	@Test
	void example1_singleTicket() {
		Gate gate = new Gate(rule(Scope.SELECTED_SESSIONS, Set.of("S1"), 1, 1));

		assertAllowed(gate.scan(S1));
		assertDenied(gate.scan(S1), TOTAL_USES_EXHAUSTED);
		assertDenied(gate.scan(S2), SESSION_NOT_INCLUDED);
	}

	@Test
	void example2_threeDayPass() {
		Gate gate = new Gate(rule(Scope.SELECTED_SESSIONS, Set.of("S1", "S2", "S3"), null, 1));

		assertAllowed(gate.scan(S1));
		assertDenied(gate.scan(S1), ALREADY_USED_IN_SESSION);
		assertAllowed(gate.scan(S2));
		assertAllowed(gate.scan(S3));
		assertDenied(gate.scan(S3), ALREADY_USED_IN_SESSION);
		assertDenied(gate.scan(S4), SESSION_NOT_INCLUDED);
	}

	@Test
	void example3_threeUsesPassOverFiveSessions() {
		Gate gate = new Gate(rule(Scope.ALL_SESSIONS, Set.of(), 3, 1));

		assertAllowed(gate.scan(S1));
		assertDenied(gate.scan(S1), ALREADY_USED_IN_SESSION);
		assertAllowed(gate.scan(S3));
		assertAllowed(gate.scan(S5));
		assertDenied(gate.scan(S2), TOTAL_USES_EXHAUSTED);
		assertDenied(gate.scan(S4), TOTAL_USES_EXHAUSTED);
	}

	@Test
	void example4_freeReentry() {
		Gate gate = new Gate(rule(Scope.ALL_SESSIONS, Set.of(), null, null));

		for (int i = 0; i < 10; i++) {
			assertAllowed(gate.scan(S1));
		}
		assertAllowed(gate.scan(S2));
	}

	@Test
	void example5_timeWindow() {
		Gate gate = new Gate(rule(Scope.ALL_SESSIONS, Set.of(), 1, 1));
		Instant opens = S1.startsAt().minus(Duration.ofMinutes(60));

		assertDenied(gate.scan(S1, opens.minusSeconds(1)), TOO_EARLY);
		assertAllowed(gate.scan(S1, opens)); // borde exacto: ya se permite
		assertThat(gate.history).hasSize(1);
	}

	@Test
	void example5_afterSessionEnds() {
		Gate gate = new Gate(rule(Scope.ALL_SESSIONS, Set.of(), 1, 1));

		assertAllowed(AccessPolicy.evaluate(gate.rule, S1, List.of(), S1.endsAt())); // borde exacto: aún vale
		assertDenied(gate.scan(S1, S1.endsAt().plusSeconds(1)), SESSION_ENDED);
	}

	// ---------- bordes y combinaciones ----------

	@Test
	void earlyEntryMinutes_isConfigurable() {
		AccessRule noEarly = new AccessRule(Scope.ALL_SESSIONS, Set.of(), null, null, 0);
		AccessRule threeHours = new AccessRule(Scope.ALL_SESSIONS, Set.of(), null, null, 180);

		assertDenied(AccessPolicy.evaluate(noEarly, S1, List.of(), S1.startsAt().minusSeconds(1)), TOO_EARLY);
		assertAllowed(AccessPolicy.evaluate(noEarly, S1, List.of(), S1.startsAt()));
		assertAllowed(AccessPolicy.evaluate(threeHours, S1, List.of(), S1.startsAt().minus(Duration.ofMinutes(180))));
	}

	@Test
	void checksApplyInDocumentedOrder() {
		AccessRule exhausted = rule(Scope.SELECTED_SESSIONS, Set.of("S1"), 1, 1);
		List<Entry> used = List.of(new Entry("S1", DAY1));

		// no incluida gana a todo
		assertDenied(AccessPolicy.evaluate(exhausted, S2, used, S2.startsAt().minus(Duration.ofDays(5))), SESSION_NOT_INCLUDED);
		// demasiado pronto gana a "ya usado"
		assertDenied(AccessPolicy.evaluate(exhausted, S1, used, S1.startsAt().minus(Duration.ofDays(5))), TOO_EARLY);
		// terminada gana a "ya usado"
		assertDenied(AccessPolicy.evaluate(exhausted, S1, used, S1.endsAt().plusSeconds(1)), SESSION_ENDED);
		// total agotado gana a "ya usado en la sesión"
		assertDenied(AccessPolicy.evaluate(exhausted, S1, used, during(S1)), TOTAL_USES_EXHAUSTED);
	}

	@Test
	void perSessionLimitGreaterThanOne() {
		Gate gate = new Gate(rule(Scope.ALL_SESSIONS, Set.of(), 5, 2));

		assertAllowed(gate.scan(S1));
		assertAllowed(gate.scan(S1));
		assertDenied(gate.scan(S1), ALREADY_USED_IN_SESSION);
		assertAllowed(gate.scan(S2));
		assertAllowed(gate.scan(S2));
		assertAllowed(gate.scan(S3));
		assertDenied(gate.scan(S3), TOTAL_USES_EXHAUSTED);
	}

	@Test
	void evaluate_doesNotMutateHistory_andIsDeterministic() {
		AccessRule rule = rule(Scope.ALL_SESSIONS, Set.of(), 2, 1);
		List<Entry> history = new ArrayList<>(List.of(new Entry("S1", DAY1)));

		Decision first = AccessPolicy.evaluate(rule, S2, history, during(S2));
		Decision second = AccessPolicy.evaluate(rule, S2, history, during(S2));

		assertThat(first).isEqualTo(second);
		assertThat(history).hasSize(1);
	}

	// ---------- validación de la regla ----------

	private static final Set<String> EVENT_SESSIONS = Set.of("S1", "S2", "S3");

	@Test
	void validate_acceptsTheTemplates() {
		assertThat(AccessPolicy.validate(rule(Scope.SELECTED_SESSIONS, Set.of("S1"), 1, 1), EVENT_SESSIONS)).isEmpty();
		assertThat(AccessPolicy.validate(rule(Scope.SELECTED_SESSIONS, Set.of("S1", "S2", "S3"), null, 1), EVENT_SESSIONS)).isEmpty();
		assertThat(AccessPolicy.validate(rule(Scope.ALL_SESSIONS, Set.of(), 3, 1), EVENT_SESSIONS)).isEmpty();
		assertThat(AccessPolicy.validate(rule(Scope.ALL_SESSIONS, Set.of(), null, null), EVENT_SESSIONS)).isEmpty();
	}

	@Test
	void validate_rejectsBrokenRules() {
		assertThat(AccessPolicy.validate(rule(Scope.SELECTED_SESSIONS, Set.of(), 1, 1), EVENT_SESSIONS)).isNotEmpty();
		assertThat(AccessPolicy.validate(rule(Scope.SELECTED_SESSIONS, Set.of("X"), 1, 1), EVENT_SESSIONS)).isNotEmpty();
		assertThat(AccessPolicy.validate(rule(Scope.ALL_SESSIONS, Set.of("S1"), 1, 1), EVENT_SESSIONS)).isNotEmpty();
		assertThat(AccessPolicy.validate(rule(Scope.ALL_SESSIONS, Set.of(), 0, 1), EVENT_SESSIONS)).isNotEmpty();
		assertThat(AccessPolicy.validate(rule(Scope.ALL_SESSIONS, Set.of(), 3, 0), EVENT_SESSIONS)).isNotEmpty();
		assertThat(AccessPolicy.validate(rule(Scope.ALL_SESSIONS, Set.of(), 2, 3), EVENT_SESSIONS)).isNotEmpty();
		assertThat(AccessPolicy.validate(new AccessRule(Scope.ALL_SESSIONS, Set.of(), null, null, -1), EVENT_SESSIONS)).isNotEmpty();
		assertThat(AccessPolicy.validate(new AccessRule(Scope.ALL_SESSIONS, Set.of(), null, null, 1441), EVENT_SESSIONS)).isNotEmpty();
	}
}
