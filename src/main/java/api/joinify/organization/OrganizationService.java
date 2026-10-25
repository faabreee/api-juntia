package api.joinify.organization;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Currency;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import api.joinify.identity.AuthUser;

@Service
class OrganizationService {

	private static final Duration INVITATION_TTL = Duration.ofDays(7);
	private static final SecureRandom RANDOM = new SecureRandom();

	private final OrganizationRepository repository;
	private final OrganizationAccess access;
	private final InvitationMailer mailer;

	OrganizationService(OrganizationRepository repository, OrganizationAccess access, InvitationMailer mailer) {
		this.repository = repository;
		this.access = access;
		this.mailer = mailer;
	}

	@Transactional
	OrganizationRepository.OrganizationRow create(AuthUser user, String name, String type, String country,
			String currency, String timezone) {
		String normalizedCountry = country.trim().toUpperCase(Locale.ROOT);
		String normalizedCurrency = currency.trim().toUpperCase(Locale.ROOT);
		if (!Set.of(Locale.getISOCountries()).contains(normalizedCountry)) {
			throw badRequest("País inválido");
		}
		if (Currency.getAvailableCurrencies().stream().noneMatch(c -> c.getCurrencyCode().equals(normalizedCurrency))) {
			throw badRequest("Moneda inválida");
		}
		if (!ZoneId.getAvailableZoneIds().contains(timezone.trim())) {
			throw badRequest("Zona horaria inválida");
		}
		String id = repository.insertOrganization(name.trim(), type, normalizedCountry, normalizedCurrency, timezone.trim());
		repository.insertMember(id, user.getId(), Role.OWNER);
		return repository.findForUser(id, user.getId()).orElseThrow();
	}

	List<OrganizationRepository.OrganizationRow> mine(AuthUser user) {
		return repository.findByUser(user.getId());
	}

	OrganizationRepository.OrganizationRow get(AuthUser user, String organizationId) {
		return repository.findForUser(organizationId, user.getId())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Organización no encontrada"));
	}

	List<OrganizationRepository.MemberRow> members(AuthUser user, String organizationId) {
		access.requireMember(organizationId, user.getId());
		return repository.findMembers(organizationId);
	}

	@Transactional
	void changeRole(AuthUser actor, String organizationId, String targetUserId, Role newRole) {
		access.requireOwner(organizationId, actor.getId());
		if (newRole == Role.OWNER) {
			throw badRequest("No se puede asignar el rol OWNER");
		}
		Role current = repository.findRole(organizationId, targetUserId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Miembro no encontrado"));
		if (current == Role.OWNER) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "No se puede cambiar el rol del propietario");
		}
		repository.updateRole(organizationId, targetUserId, newRole);
	}

	@Transactional
	void removeMember(AuthUser actor, String organizationId, String targetUserId) {
		Role actorRole = access.requireMember(organizationId, actor.getId());
		Role targetRole = repository.findRole(organizationId, targetUserId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Miembro no encontrado"));
		if (targetRole == Role.OWNER) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "El propietario no puede salir de la organización");
		}
		boolean leaving = actor.getId().equals(targetUserId);
		boolean allowed = leaving || actorRole == Role.OWNER || (actorRole == Role.ADMIN && targetRole == Role.MEMBER);
		if (!allowed) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No tienes permiso para esta acción");
		}
		repository.deleteMember(organizationId, targetUserId);
	}

	@Transactional
	OrganizationRepository.InvitationRow invite(AuthUser actor, String organizationId, String rawEmail, Role role) {
		access.requireManager(organizationId, actor.getId());
		if (role == Role.OWNER) {
			throw badRequest("No se puede invitar con el rol OWNER");
		}
		String email = rawEmail.trim().toLowerCase(Locale.ROOT);
		if (repository.isMemberEmail(organizationId, email)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Esa persona ya es miembro");
		}
		repository.deletePendingInvitations(organizationId, email); // reemplaza la invitación anterior
		String token = newToken();
		LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC).plus(INVITATION_TTL);
		String id = repository.insertInvitation(organizationId, email, role, hash(token), actor.getId(), expiresAt);
		mailer.sendInvitation(email, repository.organizationName(organizationId), role, actor.getDisplayName(), token);
		return new OrganizationRepository.InvitationRow(id, organizationId, email, role, expiresAt);
	}

	List<OrganizationRepository.InvitationRow> pendingInvitations(AuthUser actor, String organizationId) {
		access.requireManager(organizationId, actor.getId());
		return repository.findPendingInvitations(organizationId);
	}

	@Transactional
	void revokeInvitation(AuthUser actor, String organizationId, String invitationId) {
		access.requireManager(organizationId, actor.getId());
		if (!repository.deleteInvitation(organizationId, invitationId)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Invitación no encontrada");
		}
	}

	/** Acepta una invitación: el usuario autenticado debe tener el mismo correo al que se envió. */
	@Transactional
	OrganizationRepository.OrganizationRow acceptInvitation(AuthUser user, String token) {
		OrganizationRepository.InvitationRow invitation = repository.consumeInvitation(hash(token))
				.orElseThrow(() -> badRequest("Invitación inválida o expirada"));
		if (!invitation.email().equalsIgnoreCase(user.getEmail())) {
			// Lanza dentro de la transacción: se revierte el consumo y la invitación sigue válida.
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Esta invitación es para otro correo");
		}
		if (repository.findRole(invitation.organizationId(), user.getId()).isEmpty()) {
			repository.insertMember(invitation.organizationId(), user.getId(), invitation.role());
		}
		return repository.findForUser(invitation.organizationId(), user.getId()).orElseThrow();
	}

	private static ResponseStatusException badRequest(String message) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
	}

	private static String newToken() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static String hash(String token) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
