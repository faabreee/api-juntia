package api.joinify.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import jakarta.servlet.http.Cookie;

@SpringBootTest
@AutoConfigureMockMvc
class AuthFlowTests {

	private static final String PASSWORD = "una-clave-segura-123";

	@Autowired
	MockMvc mvc;

	@MockitoBean
	JavaMailSender mailSender;

	private String uniqueEmail() {
		return "user-" + UUID.randomUUID() + "@example.com";
	}

	private void register(String email) throws Exception {
		mvc.perform(post("/api/auth/register").with(csrf()).contentType("application/json")
				.content("{\"email\":\"%s\",\"password\":\"%s\",\"displayName\":\"Test\"}".formatted(email, PASSWORD)))
				.andExpect(status().isCreated());
	}

	/** Devuelve el último token enviado por correo para el destinatario. */
	private String lastTokenSentTo(String email) {
		ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
		verify(mailSender, atLeastOnce()).send(captor.capture());
		SimpleMailMessage message = captor.getAllValues().reversed().stream()
				.filter(m -> m.getTo() != null && m.getTo()[0].equals(email)).findFirst().orElseThrow();
		Matcher matcher = Pattern.compile("token=([A-Za-z0-9_-]+)").matcher(message.getText());
		assertThat(matcher.find()).isTrue();
		return matcher.group(1);
	}

	private void verifyEmail(String email) throws Exception {
		mvc.perform(post("/api/auth/verify-email").with(csrf()).contentType("application/json")
				.content("{\"token\":\"%s\"}".formatted(lastTokenSentTo(email)))).andExpect(status().isNoContent());
	}

	private MvcResult login(String email, String password) throws Exception {
		return mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json")
				.content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password))).andReturn();
	}

	@Test
	void fullFlow_register_verify_login_me_logout() throws Exception {
		String email = uniqueEmail();
		register(email);

		assertThat(login(email, PASSWORD).getResponse().getStatus()).isEqualTo(403); // sin verificar

		verifyEmail(email);
		MvcResult login = login(email, PASSWORD);
		assertThat(login.getResponse().getStatus()).isEqualTo(200);
		Cookie session = login.getResponse().getCookie("SESSION");
		assertThat(session).isNotNull();

		mvc.perform(get("/api/auth/me").cookie(session)).andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value(email));

		mvc.perform(post("/api/auth/logout").with(csrf()).cookie(session)).andExpect(status().isNoContent());
		mvc.perform(get("/api/auth/me").cookie(session)).andExpect(status().isUnauthorized());
	}

	@Test
	void duplicateEmail_isRejected() throws Exception {
		String email = uniqueEmail();
		register(email);
		mvc.perform(post("/api/auth/register").with(csrf()).contentType("application/json")
				.content("{\"email\":\"%s\",\"password\":\"%s\",\"displayName\":\"Otro\"}".formatted(email.toUpperCase(), PASSWORD)))
				.andExpect(status().isConflict());
	}

	@Test
	void wrongPassword_isUnauthorized() throws Exception {
		String email = uniqueEmail();
		register(email);
		verifyEmail(email);
		assertThat(login(email, "otra-clave-incorrecta").getResponse().getStatus()).isEqualTo(401);
		assertThat(login("nadie-" + email, PASSWORD).getResponse().getStatus()).isEqualTo(401);
	}

	@Test
	void shortPassword_isRejected() throws Exception {
		mvc.perform(post("/api/auth/register").with(csrf()).contentType("application/json")
				.content("{\"email\":\"%s\",\"password\":\"corta\",\"displayName\":\"Test\"}".formatted(uniqueEmail())))
				.andExpect(status().isBadRequest());
	}

	@Test
	void verificationToken_isSingleUse() throws Exception {
		String email = uniqueEmail();
		register(email);
		String token = lastTokenSentTo(email);
		String body = "{\"token\":\"%s\"}".formatted(token);
		mvc.perform(post("/api/auth/verify-email").with(csrf()).contentType("application/json").content(body))
				.andExpect(status().isNoContent());
		mvc.perform(post("/api/auth/verify-email").with(csrf()).contentType("application/json").content(body))
				.andExpect(status().isBadRequest());
	}

	@Test
	void passwordReset_changesPassword_andClosesSessions() throws Exception {
		String email = uniqueEmail();
		register(email);
		verifyEmail(email);
		Cookie session = login(email, PASSWORD).getResponse().getCookie("SESSION");

		mvc.perform(post("/api/auth/forgot-password").with(csrf()).contentType("application/json")
				.content("{\"email\":\"%s\"}".formatted(email))).andExpect(status().isAccepted());
		String token = lastTokenSentTo(email);

		String newPassword = "nueva-clave-segura-456";
		mvc.perform(post("/api/auth/reset-password").with(csrf()).contentType("application/json")
				.content("{\"token\":\"%s\",\"password\":\"%s\"}".formatted(token, newPassword)))
				.andExpect(status().isNoContent());

		assertThat(login(email, PASSWORD).getResponse().getStatus()).isEqualTo(401);
		assertThat(login(email, newPassword).getResponse().getStatus()).isEqualTo(200);
		mvc.perform(get("/api/auth/me").cookie(session)).andExpect(status().isUnauthorized());
	}

	@Test
	void forgotPassword_doesNotRevealUnknownEmails() throws Exception {
		mvc.perform(post("/api/auth/forgot-password").with(csrf()).contentType("application/json")
				.content("{\"email\":\"%s\"}".formatted(uniqueEmail()))).andExpect(status().isAccepted());
	}

	@Test
	void postWithoutCsrfToken_isForbidden() throws Exception {
		mvc.perform(post("/api/auth/login").contentType("application/json")
				.content("{\"email\":\"a@b.com\",\"password\":\"x\"}")).andExpect(status().isForbidden());
	}

	@Test
	void protectedEndpoint_withoutSession_isUnauthorized() throws Exception {
		mvc.perform(get("/api/anything")).andExpect(status().isUnauthorized());
	}
}
