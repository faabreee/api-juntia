package api.joinify.identity;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth")
class AuthController {

	record RegisterRequest(@NotBlank @Email @Size(max = 255) String email,
			@NotBlank @Size(min = 10, max = 72) String password,
			@NotBlank @Size(max = 100) String displayName) {
	}

	record LoginRequest(@NotBlank String email, @NotBlank String password) {
	}

	record TokenRequest(@NotBlank String token) {
	}

	record EmailRequest(@NotBlank @Email String email) {
	}

	record ResetPasswordRequest(@NotBlank String token, @NotBlank @Size(min = 10, max = 72) String password) {
	}

	record MeResponse(String id, String email, String displayName) {
	}

	private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

	private final AuthService service;
	private final AuthenticationManager authenticationManager;

	AuthController(AuthService service, AuthenticationManager authenticationManager) {
		this.service = service;
		this.authenticationManager = authenticationManager;
	}

	/** Fuerza la emisión de la cookie XSRF-TOKEN para el frontend. */
	@GetMapping("/csrf")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void csrf(@io.swagger.v3.oas.annotations.Parameter(hidden = true) CsrfToken token) {
		token.getToken();
	}

	@PostMapping("/register")
	@ResponseStatus(HttpStatus.CREATED)
	void register(@Valid @RequestBody RegisterRequest request) {
		service.register(request.email(), request.password(), request.displayName());
	}

	@PostMapping("/verify-email")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void verifyEmail(@Valid @RequestBody TokenRequest request) {
		service.verifyEmail(request.token());
	}

	@PostMapping("/resend-verification")
	@ResponseStatus(HttpStatus.ACCEPTED)
	void resendVerification(@Valid @RequestBody EmailRequest request) {
		service.resendVerification(request.email());
	}

	@PostMapping("/forgot-password")
	@ResponseStatus(HttpStatus.ACCEPTED)
	void forgotPassword(@Valid @RequestBody EmailRequest request) {
		service.forgotPassword(request.email());
	}

	@PostMapping("/reset-password")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
		service.resetPassword(request.token(), request.password());
	}

	@PostMapping("/login")
	MeResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest,
			HttpServletResponse httpResponse) {
		Authentication authentication;
		try {
			authentication = authenticationManager.authenticate(
					UsernamePasswordAuthenticationToken.unauthenticated(AuthService.normalize(request.email()), request.password()));
		} catch (AuthenticationException e) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales inválidas");
		}
		AuthUser user = (AuthUser) authentication.getPrincipal();
		if (!user.isEmailVerified()) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Debes verificar tu correo antes de entrar");
		}
		if (httpRequest.getSession(false) != null) {
			httpRequest.changeSessionId(); // evita fijación de sesión
		}
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(authentication);
		SecurityContextHolder.setContext(context);
		securityContextRepository.saveContext(context, httpRequest, httpResponse);
		return new MeResponse(user.getId(), user.getEmail(), user.getDisplayName());
	}

	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void logout(HttpServletRequest request) {
		var session = request.getSession(false);
		if (session != null) {
			session.invalidate();
		}
		SecurityContextHolder.clearContext();
	}

	@GetMapping("/me")
	MeResponse me(Authentication authentication) {
		if (authentication == null || !(authentication.getPrincipal() instanceof AuthUser user)) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}
		return new MeResponse(user.getId(), user.getEmail(), user.getDisplayName());
	}
}
