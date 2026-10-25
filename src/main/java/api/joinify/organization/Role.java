package api.joinify.organization;

/** Rol de un usuario dentro de una organización. */
public enum Role {
	OWNER, ADMIN, MEMBER;

	public boolean canManage() {
		return this == OWNER || this == ADMIN;
	}
}
