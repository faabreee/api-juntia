package api.joinify.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import api.joinify.ticket.TicketTokenService.Verified;

class TicketTokenServiceTests {

	private static final String TICKET = UUID.randomUUID().toString();
	private static final String EVENT = UUID.randomUUID().toString();

	private static KeyPair newKeyPair() throws Exception {
		return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
	}

	private static String priv(KeyPair pair) {
		return Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
	}

	private static String pub(KeyPair pair) {
		return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
	}

	private static TicketTokenService service(String kid, KeyPair signing, String publicKeys) {
		return new TicketTokenService(kid, priv(signing), publicKeys, false);
	}

	private static TicketTokenService simple() throws Exception {
		KeyPair pair = newKeyPair();
		return service("k1", pair, "k1:" + pub(pair));
	}

	@Test
	void tokenRoundTrips_andCarriesOnlyIdentifiers() throws Exception {
		TicketTokenService tokens = simple();

		String token = tokens.issue(TICKET, EVENT);

		assertThat(tokens.verify(token)).contains(new Verified(TICKET, EVENT, "k1"));
		assertThat(token).startsWith("JT1.k1.");
		assertThat(token.length()).as("cabe en un QR pequeño").isLessThan(160);
	}

	@Test
	void sameTicketAlwaysProducesTheSameToken() throws Exception {
		TicketTokenService tokens = simple();

		assertThat(tokens.issue(TICKET, EVENT)).isEqualTo(tokens.issue(TICKET, EVENT));
		assertThat(tokens.issue(TICKET, EVENT)).isNotEqualTo(tokens.issue(UUID.randomUUID().toString(), EVENT));
	}

	@Test
	void tamperedTokens_areRejected() throws Exception {
		TicketTokenService tokens = simple();
		String token = tokens.issue(TICKET, EVENT);
		String[] parts = token.split("\\.");

		// otro boleto con la firma del primero
		String other = tokens.issue(UUID.randomUUID().toString(), EVENT).split("\\.")[2];
		assertThat(tokens.verify(String.join(".", parts[0], parts[1], other, parts[3], parts[4]))).isEmpty();
		// otro evento
		String otherEvent = tokens.issue(TICKET, UUID.randomUUID().toString()).split("\\.")[3];
		assertThat(tokens.verify(String.join(".", parts[0], parts[1], parts[2], otherEvent, parts[4]))).isEmpty();
		// firma alterada
		char flipped = parts[4].charAt(0) == 'A' ? 'B' : 'A';
		assertThat(tokens.verify(String.join(".", parts[0], parts[1], parts[2], parts[3], flipped + parts[4].substring(1)))).isEmpty();
		// firma truncada
		assertThat(tokens.verify(token.substring(0, token.length() - 4))).isEmpty();
	}

	@Test
	void malformedInput_neverThrows() throws Exception {
		TicketTokenService tokens = simple();

		for (String bad : new String[] { null, "", " ", "JT1", "JT1.k1", "JT1.k1.a.b", "JT1.k1.a.b.c.d", "XX1.k1.a.b.c", "JT1.k1.!!!.@@@.###",
				"JT1.k1.AAAA.AAAA.AAAA", "...", "JT1....", "no es un token" }) {
			assertThat(tokens.verify(bad)).as("entrada: %s", bad).isEmpty();
		}
	}

	@Test
	void tokenSignedByAnotherKey_isRejected() throws Exception {
		KeyPair mine = newKeyPair();
		KeyPair attacker = newKeyPair();
		TicketTokenService tokens = service("k1", mine, "k1:" + pub(mine));
		// el atacante firma con su clave pero usa el mismo kid
		TicketTokenService forger = service("k1", attacker, "k1:" + pub(attacker));

		assertThat(tokens.verify(forger.issue(TICKET, EVENT))).isEmpty();
	}

	@Test
	void unknownKeyId_isRejected() throws Exception {
		KeyPair pair = newKeyPair();
		TicketTokenService issuer = service("k9", pair, "k9:" + pub(pair));
		TicketTokenService verifier = simple();

		assertThat(verifier.verify(issuer.issue(TICKET, EVENT))).isEmpty();
	}

	@Test
	void keyRotation_oldTicketsKeepWorking() throws Exception {
		KeyPair oldKey = newKeyPair();
		KeyPair newKey = newKeyPair();
		TicketTokenService before = service("k1", oldKey, "k1:" + pub(oldKey));
		String oldToken = before.issue(TICKET, EVENT);

		// después de rotar: se firma con k2 y se conservan las públicas de ambas
		TicketTokenService after = service("k2", newKey, "k1:" + pub(oldKey) + ",k2:" + pub(newKey));

		assertThat(after.verify(oldToken)).map(Verified::keyId).contains("k1");
		assertThat(after.verify(after.issue(TICKET, EVENT))).map(Verified::keyId).contains("k2");
		// y quien aún no conoce k2 no puede verificar lo nuevo
		assertThat(before.verify(after.issue(TICKET, EVENT))).isEmpty();
	}

	@Test
	void publicKeyThatDoesNotMatchTheSigningKey_failsAtStartup() throws Exception {
		KeyPair a = newKeyPair();
		KeyPair b = newKeyPair();

		assertThatThrownBy(() -> service("k1", a, "k1:" + pub(b))).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new TicketTokenService("k1", "no-es-base64!!", "k1:xx", false)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new TicketTokenService("k 1", priv(a), "k1:" + pub(a), false)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new TicketTokenService("k1", priv(a), "sin-formato", false)).isInstanceOf(IllegalStateException.class);
	}
}
