package api.joinify.organization;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import api.joinify.identity.AuthUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api")
@Tag(name = "Organizaciones")
class OrganizationController {

	enum OrganizationType { PERSON, COMPANY }

	record CreateOrganizationRequest(@NotBlank @Size(min = 2, max = 120) String name, @NotNull OrganizationType type,
			@NotBlank @Pattern(regexp = "[A-Za-z]{2}") String country,
			@NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currency,
			@NotBlank @Size(max = 64) String timezone) {
	}

	record OrganizationResponse(String id, String name, String type, String country, String currency,
			String timezone, Role role) {
		static OrganizationResponse of(OrganizationRepository.OrganizationRow r) {
			return new OrganizationResponse(r.id(), r.name(), r.type(), r.country(), r.currency(), r.timezone(), r.role());
		}
	}

	record MemberResponse(String userId, String email, String displayName, Role role) {
	}

	record ChangeRoleRequest(@NotNull Role role) {
	}

	record InviteRequest(@NotBlank @Email @Size(max = 255) String email, @NotNull Role role) {
	}

	record InvitationResponse(String id, String email, Role role, Instant expiresAt) {
		static InvitationResponse of(OrganizationRepository.InvitationRow r) {
			return new InvitationResponse(r.id(), r.email(), r.role(), r.expiresAt().toInstant(ZoneOffset.UTC));
		}
	}

	record AcceptInvitationRequest(@NotBlank String token) {
	}

	private final OrganizationService service;

	OrganizationController(OrganizationService service) {
		this.service = service;
	}

	@PostMapping("/organizations")
	@ResponseStatus(HttpStatus.CREATED)
	OrganizationResponse createOrganization(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody CreateOrganizationRequest r) {
		return OrganizationResponse.of(
				service.create(user, r.name(), r.type().name(), r.country(), r.currency(), r.timezone()));
	}

	@GetMapping("/organizations")
	List<OrganizationResponse> listMyOrganizations(@AuthenticationPrincipal AuthUser user) {
		return service.mine(user).stream().map(OrganizationResponse::of).toList();
	}

	@GetMapping("/organizations/{organizationId}")
	OrganizationResponse getOrganization(@AuthenticationPrincipal AuthUser user, @PathVariable String organizationId) {
		return OrganizationResponse.of(service.get(user, organizationId));
	}

	@GetMapping("/organizations/{organizationId}/members")
	List<MemberResponse> listMembers(@AuthenticationPrincipal AuthUser user, @PathVariable String organizationId) {
		return service.members(user, organizationId).stream()
				.map(m -> new MemberResponse(m.userId(), m.email(), m.displayName(), m.role())).toList();
	}

	@PutMapping("/organizations/{organizationId}/members/{userId}/role")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void changeRole(@AuthenticationPrincipal AuthUser user, @PathVariable String organizationId,
			@PathVariable String userId, @Valid @RequestBody ChangeRoleRequest r) {
		service.changeRole(user, organizationId, userId, r.role());
	}

	@DeleteMapping("/organizations/{organizationId}/members/{userId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void removeMember(@AuthenticationPrincipal AuthUser user, @PathVariable String organizationId,
			@PathVariable String userId) {
		service.removeMember(user, organizationId, userId);
	}

	@PostMapping("/organizations/{organizationId}/invitations")
	@ResponseStatus(HttpStatus.CREATED)
	InvitationResponse inviteMember(@AuthenticationPrincipal AuthUser user, @PathVariable String organizationId,
			@Valid @RequestBody InviteRequest r) {
		return InvitationResponse.of(service.invite(user, organizationId, r.email(), r.role()));
	}

	@GetMapping("/organizations/{organizationId}/invitations")
	List<InvitationResponse> listInvitations(@AuthenticationPrincipal AuthUser user, @PathVariable String organizationId) {
		return service.pendingInvitations(user, organizationId).stream().map(InvitationResponse::of).toList();
	}

	@DeleteMapping("/organizations/{organizationId}/invitations/{invitationId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void revokeInvitation(@AuthenticationPrincipal AuthUser user, @PathVariable String organizationId,
			@PathVariable String invitationId) {
		service.revokeInvitation(user, organizationId, invitationId);
	}

	@PostMapping("/invitations/accept")
	OrganizationResponse acceptInvitation(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody AcceptInvitationRequest r) {
		return OrganizationResponse.of(service.acceptInvitation(user, r.token()));
	}
}
