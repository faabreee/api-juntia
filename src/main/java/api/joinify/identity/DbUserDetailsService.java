package api.joinify.identity;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
class DbUserDetailsService implements UserDetailsService {

	private final UserRepository users;

	DbUserDetailsService(UserRepository users) {
		this.users = users;
	}

	@Override
	public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
		return users.findByEmail(AuthService.normalize(email))
				.orElseThrow(() -> new UsernameNotFoundException("Usuario no encontrado"));
	}
}
