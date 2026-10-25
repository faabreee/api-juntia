package api.joinify.ticket;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * QR firmado de un boleto (ADR 0009). El token es {@code JT1.<kid>.<boleto>.<evento>.<firma>} con la firma Ed25519
 * de los cuatro primeros campos. Lleva solo identificadores: la regla de acceso y las sesiones se consultan al
 * validar, así que cambiar una regla no invalida QR ya emitidos y un QR robado no revela datos.
 *
 * La firma de Ed25519 es determinista: el mismo boleto produce siempre el mismo token (no hay que guardarlo).
 * Para rotar claves se añade la nueva clave pública a {@code app.tickets.public-keys}, se cambia el {@code kid} de
 * firma y se conservan las públicas antiguas mientras queden boletos vigentes.
 */
@Service
public class TicketTokenService {

	public record Verified(String ticketId, String eventId, String keyId) {
	}

	private static final Logger log = LoggerFactory.getLogger(TicketTokenService.class);
	private static final String PREFIX = "JT1";
	private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
	private static final Base64.Decoder B64D = Base64.getUrlDecoder();

	private final String keyId;
	private final PrivateKey signingKey;
	private final Map<String, PublicKey> publicKeys = new HashMap<>();

	@org.springframework.beans.factory.annotation.Autowired
	TicketTokenService(@Value("${app.tickets.key-id}") String keyId, @Value("${app.tickets.signing-key}") String signingKey,
			@Value("${app.tickets.public-keys}") String publicKeys) {
		this(keyId, signingKey, publicKeys, true);
	}

	/** Constructor para pruebas (sin el aviso de clave de desarrollo). */
	TicketTokenService(String keyId, String signingKey, String publicKeys, boolean warnOnDevKey) {
		if (keyId == null || !keyId.matches("[A-Za-z0-9_-]{1,16}")) {
			throw new IllegalStateException("app.tickets.key-id inválido");
		}
		this.keyId = keyId;
		try {
			KeyFactory factory = KeyFactory.getInstance("Ed25519");
			this.signingKey = factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(signingKey.trim())));
			for (String entry : publicKeys.split(",")) {
				String[] parts = entry.trim().split(":", 2);
				if (parts.length != 2) {
					throw new IllegalStateException("app.tickets.public-keys debe tener el formato kid:clave[,kid:clave]");
				}
				this.publicKeys.put(parts[0], factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(parts[1]))));
			}
		} catch (GeneralSecurityException | IllegalArgumentException e) {
			throw new IllegalStateException("Claves de firma de boletos inválidas", e);
		}
		// Fallar al arrancar si la clave pública vigente no corresponde a la privada: evita emitir QR inverificables.
		Verified probe = verify(issue("00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002"))
				.orElseThrow(() -> new IllegalStateException("La clave pública de '" + keyId + "' no corresponde a la clave de firma"));
		if (warnOnDevKey && "dev1".equals(probe.keyId())) {
			log.warn("Se usa la clave de firma de boletos de DESARROLLO (kid=dev1): es pública. Define TICKET_SIGNING_KEY en producción.");
		}
	}

	public String issue(String ticketId, String eventId) {
		String signed = PREFIX + "." + keyId + "." + encode(ticketId) + "." + encode(eventId);
		return signed + "." + B64.encodeToString(sign(signed));
	}

	/** Comprueba la firma. Vacío si el token está mal formado, lo firmó otra clave o fue alterado. */
	public Optional<Verified> verify(String token) {
		if (token == null) {
			return Optional.empty();
		}
		String[] parts = token.split("\\.", -1);
		if (parts.length != 5 || !PREFIX.equals(parts[0])) {
			return Optional.empty();
		}
		PublicKey key = publicKeys.get(parts[1]);
		if (key == null) {
			return Optional.empty();
		}
		try {
			Signature verifier = Signature.getInstance("Ed25519");
			verifier.initVerify(key);
			verifier.update(String.join(".", parts[0], parts[1], parts[2], parts[3]).getBytes(StandardCharsets.US_ASCII));
			if (!verifier.verify(B64D.decode(parts[4]))) {
				return Optional.empty();
			}
			return Optional.of(new Verified(decode(parts[2]), decode(parts[3]), parts[1]));
		} catch (GeneralSecurityException | IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	private byte[] sign(String signed) {
		try {
			Signature signature = Signature.getInstance("Ed25519");
			signature.initSign(signingKey);
			signature.update(signed.getBytes(StandardCharsets.US_ASCII));
			return signature.sign();
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String encode(String uuid) {
		UUID id = UUID.fromString(uuid);
		return B64.encodeToString(ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array());
	}

	private static String decode(String segment) {
		ByteBuffer buffer = ByteBuffer.wrap(B64D.decode(segment));
		if (buffer.remaining() != 16) {
			throw new IllegalArgumentException("Identificador inválido");
		}
		return new UUID(buffer.getLong(), buffer.getLong()).toString();
	}
}
