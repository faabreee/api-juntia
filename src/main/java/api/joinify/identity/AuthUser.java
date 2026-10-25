package api.joinify.identity;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;

import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Usuario autenticado. Se guarda serializado en la sesión, por eso nunca conserva el hash de la contraseña.
 */
public class AuthUser implements UserDetails, CredentialsContainer, Serializable {

	private static final long serialVersionUID = 1L;

	private final String id;
	private final String email;
	private final String displayName;
	private final boolean emailVerified;
	private String passwordHash;

	AuthUser(String id, String email, String passwordHash, String displayName, boolean emailVerified) {
		this.id = id;
		this.email = email;
		this.passwordHash = passwordHash;
		this.displayName = displayName;
		this.emailVerified = emailVerified;
	}

	public String getId() {
		return id;
	}

	public String getEmail() {
		return email;
	}

	public String getDisplayName() {
		return displayName;
	}

	public boolean isEmailVerified() {
		return emailVerified;
	}

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		return List.of();
	}

	@Override
	public String getPassword() {
		return passwordHash;
	}

	@Override
	public String getUsername() {
		return email;
	}

	@Override
	public void eraseCredentials() {
		this.passwordHash = null;
	}
}
