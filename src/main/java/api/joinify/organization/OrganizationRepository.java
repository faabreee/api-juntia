package api.joinify.organization;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class OrganizationRepository {

	record OrganizationRow(String id, String name, String type, String country, String currency, String timezone,
			Role role) {
	}

	record MemberRow(String userId, String email, String displayName, Role role) {
	}

	record InvitationRow(String id, String organizationId, String email, Role role, LocalDateTime expiresAt) {
	}

	private final JdbcClient jdbc;

	OrganizationRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	private static LocalDateTime now() {
		return LocalDateTime.now(ZoneOffset.UTC);
	}

	String insertOrganization(String name, String type, String country, String currency, String timezone) {
		String id = UUID.randomUUID().toString();
		jdbc.sql("INSERT INTO organizations (id, name, type, country, currency, timezone, created_at) VALUES (:id, :name, :type, :country, :currency, :tz, :now)")
				.param("id", id).param("name", name).param("type", type).param("country", country)
				.param("currency", currency).param("tz", timezone).param("now", now()).update();
		return id;
	}

	void insertMember(String organizationId, String userId, Role role) {
		jdbc.sql("INSERT INTO organization_members (organization_id, user_id, role, created_at) VALUES (:org, :user, :role, :now)")
				.param("org", organizationId).param("user", userId).param("role", role.name()).param("now", now()).update();
	}

	Optional<Role> findRole(String organizationId, String userId) {
		return jdbc.sql("SELECT role FROM organization_members WHERE organization_id = :org AND user_id = :user")
				.param("org", organizationId).param("user", userId)
				.query((rs, i) -> Role.valueOf(rs.getString("role"))).optional();
	}

	private static OrganizationRow mapOrganization(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
		return new OrganizationRow(rs.getString("id"), rs.getString("name"), rs.getString("type"),
				rs.getString("country"), rs.getString("currency"), rs.getString("timezone"),
				Role.valueOf(rs.getString("role")));
	}

	List<OrganizationRow> findByUser(String userId) {
		return jdbc.sql("SELECT o.id, o.name, o.type, o.country, o.currency, o.timezone, m.role FROM organizations o JOIN organization_members m ON m.organization_id = o.id WHERE m.user_id = :user ORDER BY o.name")
				.param("user", userId).query(OrganizationRepository::mapOrganization).list();
	}

	Optional<OrganizationRow> findForUser(String organizationId, String userId) {
		return jdbc.sql("SELECT o.id, o.name, o.type, o.country, o.currency, o.timezone, m.role FROM organizations o JOIN organization_members m ON m.organization_id = o.id WHERE o.id = :org AND m.user_id = :user")
				.param("org", organizationId).param("user", userId).query(OrganizationRepository::mapOrganization).optional();
	}

	/** Lee nombre y correo de la tabla de usuarios solo para mostrar el listado de miembros. */
	List<MemberRow> findMembers(String organizationId) {
		return jdbc.sql("SELECT u.id, u.email, u.display_name, m.role FROM organization_members m JOIN users u ON u.id = m.user_id WHERE m.organization_id = :org ORDER BY m.created_at, u.email")
				.param("org", organizationId)
				.query((rs, i) -> new MemberRow(rs.getString("id"), rs.getString("email"), rs.getString("display_name"),
						Role.valueOf(rs.getString("role"))))
				.list();
	}

	boolean isMemberEmail(String organizationId, String email) {
		return jdbc.sql("SELECT COUNT(*) FROM organization_members m JOIN users u ON u.id = m.user_id WHERE m.organization_id = :org AND u.email = :email")
				.param("org", organizationId).param("email", email).query(Long.class).single() > 0;
	}

	void updateRole(String organizationId, String userId, Role role) {
		jdbc.sql("UPDATE organization_members SET role = :role WHERE organization_id = :org AND user_id = :user")
				.param("role", role.name()).param("org", organizationId).param("user", userId).update();
	}

	void deleteMember(String organizationId, String userId) {
		jdbc.sql("DELETE FROM organization_members WHERE organization_id = :org AND user_id = :user")
				.param("org", organizationId).param("user", userId).update();
	}

	void deletePendingInvitations(String organizationId, String email) {
		jdbc.sql("DELETE FROM organization_invitations WHERE organization_id = :org AND email = :email AND accepted_at IS NULL")
				.param("org", organizationId).param("email", email).update();
	}

	String insertInvitation(String organizationId, String email, Role role, String tokenHash, String invitedBy,
			LocalDateTime expiresAt) {
		String id = UUID.randomUUID().toString();
		jdbc.sql("INSERT INTO organization_invitations (id, organization_id, email, role, token_hash, invited_by, created_at, expires_at) VALUES (:id, :org, :email, :role, :hash, :by, :now, :exp)")
				.param("id", id).param("org", organizationId).param("email", email).param("role", role.name())
				.param("hash", tokenHash).param("by", invitedBy).param("now", now()).param("exp", expiresAt).update();
		return id;
	}

	List<InvitationRow> findPendingInvitations(String organizationId) {
		return jdbc.sql("SELECT id, organization_id, email, role, expires_at FROM organization_invitations WHERE organization_id = :org AND accepted_at IS NULL AND expires_at > :now ORDER BY created_at")
				.param("org", organizationId).param("now", now()).query(OrganizationRepository::mapInvitation).list();
	}

	private static InvitationRow mapInvitation(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
		return new InvitationRow(rs.getString("id"), rs.getString("organization_id"), rs.getString("email"),
				Role.valueOf(rs.getString("role")), rs.getObject("expires_at", LocalDateTime.class));
	}

	boolean deleteInvitation(String organizationId, String invitationId) {
		return jdbc.sql("DELETE FROM organization_invitations WHERE id = :id AND organization_id = :org AND accepted_at IS NULL")
				.param("id", invitationId).param("org", organizationId).update() > 0;
	}

	/** Marca la invitación como aceptada si sigue vigente y devuelve sus datos. */
	Optional<InvitationRow> consumeInvitation(String tokenHash) {
		int updated = jdbc.sql("UPDATE organization_invitations SET accepted_at = :now WHERE token_hash = :hash AND accepted_at IS NULL AND expires_at > :now")
				.param("now", now()).param("hash", tokenHash).update();
		if (updated == 0) {
			return Optional.empty();
		}
		return jdbc.sql("SELECT id, organization_id, email, role, expires_at FROM organization_invitations WHERE token_hash = :hash")
				.param("hash", tokenHash).query(OrganizationRepository::mapInvitation).optional();
	}

	String organizationName(String organizationId) {
		return jdbc.sql("SELECT name FROM organizations WHERE id = :id").param("id", organizationId)
				.query(String.class).single();
	}

	String organizationCurrency(String organizationId) {
		return jdbc.sql("SELECT currency FROM organizations WHERE id = :id").param("id", organizationId)
				.query(String.class).single();
	}
}
