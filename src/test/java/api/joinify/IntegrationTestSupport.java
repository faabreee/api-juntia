package api.joinify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

import jakarta.servlet.http.Cookie;

/** Base para pruebas de integración: MockMvc, correo simulado y usuarios con sesión. */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTestSupport {

	protected static final String PASSWORD = "una-clave-segura-123";

	@Autowired
	protected MockMvc mvc;

	@MockitoBean
	protected JavaMailSender mailSender;

	/** Los correos de boletos se arman como MimeMessage: el simulado debe poder crear uno real y vacío. */
	@BeforeEach
	void stubMimeMessageCreation() {
		org.mockito.Mockito.when(mailSender.createMimeMessage())
				.thenAnswer(invocation -> new jakarta.mail.internet.MimeMessage((jakarta.mail.Session) null));
	}

	@Autowired(required = false)
	@org.springframework.beans.factory.annotation.Qualifier("applicationTaskExecutor")
	private java.util.concurrent.Executor asyncExecutor;

	/**
	 * Los correos de boletos se envían en segundo plano. Se espera a que terminen antes de pasar a la siguiente prueba:
	 * si no, un envío tardío tocaría el simulado de correo de la prueba siguiente mientras ésta lo configura.
	 */
	@AfterEach
	void drainAsyncWork() throws InterruptedException {
		if (asyncExecutor instanceof org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor pool) {
			long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
			while ((pool.getActiveCount() > 0 || !pool.getThreadPoolExecutor().getQueue().isEmpty()) && System.nanoTime() < deadline) {
				Thread.sleep(20);
			}
		}
	}

	protected record TestUser(String id, String email, Cookie session) {
	}

	protected String uniqueEmail() {
		return "user-" + UUID.randomUUID() + "@example.com";
	}

	/** Registra, verifica el correo e inicia sesión. */
	protected TestUser newUser() throws Exception {
		return newUser(uniqueEmail());
	}

	protected TestUser newUser(String email) throws Exception {
		mvc.perform(post("/api/auth/register").with(csrf()).contentType("application/json")
				.content("{\"email\":\"%s\",\"password\":\"%s\",\"displayName\":\"Test %s\"}".formatted(email, PASSWORD, email)))
				.andExpect(status().isCreated());
		mvc.perform(post("/api/auth/verify-email").with(csrf()).contentType("application/json")
				.content("{\"token\":\"%s\"}".formatted(lastTokenSentTo(email)))).andExpect(status().isNoContent());
		var login = mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json")
				.content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
				.andExpect(status().isOk()).andReturn().getResponse();
		String id = com.jayway.jsonpath.JsonPath.read(login.getContentAsString(), "$.id");
		return new TestUser(id, email, login.getCookie("SESSION"));
	}

	/** Último token (query param "token=") enviado por correo a esa dirección. */
	protected String lastTokenSentTo(String email) {
		ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
		verify(mailSender, atLeastOnce()).send(captor.capture());
		SimpleMailMessage message = captor.getAllValues().reversed().stream()
				.filter(m -> m.getTo() != null && m.getTo()[0].equals(email)).findFirst().orElseThrow();
		Matcher matcher = Pattern.compile("token=([A-Za-z0-9_-]+)").matcher(message.getText());
		assertThat(matcher.find()).isTrue();
		return matcher.group(1);
	}

	protected <B extends AbstractMockHttpServletRequestBuilder<B>> B as(TestUser user, B request) {
		return request.cookie(user.session()).with(csrf());
	}
}
