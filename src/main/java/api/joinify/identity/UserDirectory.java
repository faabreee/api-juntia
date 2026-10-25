package api.joinify.identity;

import java.util.Optional;

import org.springframework.stereotype.Service;

/** API pública del módulo de identidad para quien necesita contactar a un usuario (por ejemplo, enviarle sus boletos). */
@Service
public class UserDirectory {

	public record Contact(String id, String email, String displayName) {
	}

	private final UserRepository users;

	UserDirectory(UserRepository users) {
		this.users = users;
	}

	public Optional<Contact> contactOf(String userId) {
		return users.findContactById(userId);
	}
}
