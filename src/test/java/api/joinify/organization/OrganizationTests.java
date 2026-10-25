package api.joinify.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

import com.jayway.jsonpath.JsonPath;

import api.joinify.IntegrationTestSupport;

class OrganizationTests extends IntegrationTestSupport {

	private static final String ORG = "{\"name\":\"%s\",\"type\":\"COMPANY\",\"country\":\"PE\",\"currency\":\"PEN\",\"timezone\":\"America/Lima\"}";

	private String createOrg(TestUser owner, String name) throws Exception {
		String body = mvc.perform(as(owner, post("/api/organizations")).contentType("application/json")
				.content(ORG.formatted(name))).andExpect(status().isCreated())
				.andExpect(jsonPath("$.role").value("OWNER")).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.id");
	}

	/** Invita por correo y acepta con el usuario dado; devuelve su rol resultante. */
	private void join(String orgId, TestUser owner, TestUser invitee, String role) throws Exception {
		mvc.perform(as(owner, post("/api/organizations/" + orgId + "/invitations")).contentType("application/json")
				.content("{\"email\":\"%s\",\"role\":\"%s\"}".formatted(invitee.email(), role)))
				.andExpect(status().isCreated());
		mvc.perform(as(invitee, post("/api/invitations/accept")).contentType("application/json")
				.content("{\"token\":\"%s\"}".formatted(lastTokenSentTo(invitee.email()))))
				.andExpect(status().isOk()).andExpect(jsonPath("$.role").value(role));
	}

	@Test
	void createOrganization_makesCreatorOwner_andListsIt() throws Exception {
		TestUser owner = newUser();
		String orgId = createOrg(owner, "Mi Productora");

		mvc.perform(as(owner, get("/api/organizations"))).andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.id=='%s')].currency".formatted(orgId)).value("PEN"));
		mvc.perform(as(owner, get("/api/organizations/" + orgId + "/members"))).andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].role").value("OWNER"));
	}

	@Test
	void createOrganization_validatesCountryCurrencyAndTimezone() throws Exception {
		TestUser owner = newUser();
		for (String body : new String[] {
				"{\"name\":\"X Org\",\"type\":\"COMPANY\",\"country\":\"ZZ\",\"currency\":\"PEN\",\"timezone\":\"America/Lima\"}",
				"{\"name\":\"X Org\",\"type\":\"COMPANY\",\"country\":\"PE\",\"currency\":\"QQQ\",\"timezone\":\"America/Lima\"}",
				"{\"name\":\"X Org\",\"type\":\"COMPANY\",\"country\":\"PE\",\"currency\":\"PEN\",\"timezone\":\"Mars/Base\"}",
				"{\"name\":\"X Org\",\"type\":\"OTHER\",\"country\":\"PE\",\"currency\":\"PEN\",\"timezone\":\"America/Lima\"}" }) {
			mvc.perform(as(owner, post("/api/organizations")).contentType("application/json").content(body))
					.andExpect(status().isBadRequest());
		}
	}

	@Test
	void unauthenticated_isUnauthorized() throws Exception {
		mvc.perform(get("/api/organizations")).andExpect(status().isUnauthorized());
	}

	@Test
	void invitation_flow_addsMemberWithRole() throws Exception {
		TestUser owner = newUser();
		TestUser guest = newUser();
		String orgId = createOrg(owner, "Festival Norte");

		join(orgId, owner, guest, "ADMIN");

		mvc.perform(as(owner, get("/api/organizations/" + orgId + "/members"))).andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2));
		mvc.perform(as(guest, get("/api/organizations"))).andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.id=='%s')].role".formatted(orgId)).value("ADMIN"));
	}

	@Test
	void invitation_cannotBeAcceptedByAnotherEmail_andStaysValid() throws Exception {
		TestUser owner = newUser();
		TestUser invited = newUser();
		TestUser intruder = newUser();
		String orgId = createOrg(owner, "Teatro Sur");

		mvc.perform(as(owner, post("/api/organizations/" + orgId + "/invitations")).contentType("application/json")
				.content("{\"email\":\"%s\",\"role\":\"MEMBER\"}".formatted(invited.email()))).andExpect(status().isCreated());
		String token = lastTokenSentTo(invited.email());
		String body = "{\"token\":\"%s\"}".formatted(token);

		mvc.perform(as(intruder, post("/api/invitations/accept")).contentType("application/json").content(body))
				.andExpect(status().isForbidden());
		mvc.perform(as(intruder, get("/api/organizations/" + orgId))).andExpect(status().isNotFound());

		mvc.perform(as(invited, post("/api/invitations/accept")).contentType("application/json").content(body))
				.andExpect(status().isOk());
		// un solo uso
		mvc.perform(as(invited, post("/api/invitations/accept")).contentType("application/json").content(body))
				.andExpect(status().isBadRequest());
	}

	@Test
	void reinvite_replacesPreviousInvitation() throws Exception {
		TestUser owner = newUser();
		TestUser guest = newUser();
		String orgId = createOrg(owner, "Cine Club");
		String invite = "{\"email\":\"%s\",\"role\":\"MEMBER\"}".formatted(guest.email());

		mvc.perform(as(owner, post("/api/organizations/" + orgId + "/invitations")).contentType("application/json").content(invite));
		String oldToken = lastTokenSentTo(guest.email());
		mvc.perform(as(owner, post("/api/organizations/" + orgId + "/invitations")).contentType("application/json").content(invite));

		mvc.perform(as(owner, get("/api/organizations/" + orgId + "/invitations"))).andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1));
		mvc.perform(as(guest, post("/api/invitations/accept")).contentType("application/json")
				.content("{\"token\":\"%s\"}".formatted(oldToken))).andExpect(status().isBadRequest());
	}

	@Test
	void revokedInvitation_cannotBeAccepted() throws Exception {
		TestUser owner = newUser();
		TestUser guest = newUser();
		String orgId = createOrg(owner, "Radio Libre");
		String body = mvc.perform(as(owner, post("/api/organizations/" + orgId + "/invitations")).contentType("application/json")
				.content("{\"email\":\"%s\",\"role\":\"MEMBER\"}".formatted(guest.email()))).andReturn().getResponse().getContentAsString();
		String invitationId = JsonPath.read(body, "$.id");
		String token = lastTokenSentTo(guest.email());

		mvc.perform(as(owner, delete("/api/organizations/" + orgId + "/invitations/" + invitationId))).andExpect(status().isNoContent());
		mvc.perform(as(guest, post("/api/invitations/accept")).contentType("application/json")
				.content("{\"token\":\"%s\"}".formatted(token))).andExpect(status().isBadRequest());
	}

	@Test
	void roles_areEnforced() throws Exception {
		TestUser owner = newUser();
		TestUser admin = newUser();
		TestUser member = newUser();
		TestUser outsider = newUser();
		String orgId = createOrg(owner, "Arena Lima");
		join(orgId, owner, admin, "ADMIN");
		join(orgId, owner, member, "MEMBER");
		String invite = "{\"email\":\"%s\",\"role\":\"MEMBER\"}".formatted(outsider.email());

		// MEMBER no gestiona nada
		mvc.perform(as(member, post("/api/organizations/" + orgId + "/invitations")).contentType("application/json").content(invite))
				.andExpect(status().isForbidden());
		mvc.perform(as(member, get("/api/organizations/" + orgId + "/invitations"))).andExpect(status().isForbidden());
		// ADMIN invita pero no cambia roles
		mvc.perform(as(admin, post("/api/organizations/" + orgId + "/invitations")).contentType("application/json").content(invite))
				.andExpect(status().isCreated());
		mvc.perform(as(admin, put("/api/organizations/" + orgId + "/members/" + member.id() + "/role")).contentType("application/json")
				.content("{\"role\":\"ADMIN\"}")).andExpect(status().isForbidden());
		// ADMIN puede quitar un MEMBER pero no a otro ADMIN ni al OWNER
		mvc.perform(as(admin, delete("/api/organizations/" + orgId + "/members/" + owner.id()))).andExpect(status().isConflict());
		// OWNER cambia roles, pero OWNER no es asignable ni modificable
		mvc.perform(as(owner, put("/api/organizations/" + orgId + "/members/" + member.id() + "/role")).contentType("application/json")
				.content("{\"role\":\"ADMIN\"}")).andExpect(status().isNoContent());
		mvc.perform(as(owner, put("/api/organizations/" + orgId + "/members/" + member.id() + "/role")).contentType("application/json")
				.content("{\"role\":\"OWNER\"}")).andExpect(status().isBadRequest());
		mvc.perform(as(owner, put("/api/organizations/" + orgId + "/members/" + owner.id() + "/role")).contentType("application/json")
				.content("{\"role\":\"MEMBER\"}")).andExpect(status().isConflict());
		// ahora member es ADMIN: ADMIN no puede quitar a otro ADMIN
		mvc.perform(as(admin, delete("/api/organizations/" + orgId + "/members/" + member.id()))).andExpect(status().isForbidden());
		// OWNER lo degrada y el ADMIN ya puede quitarlo
		mvc.perform(as(owner, put("/api/organizations/" + orgId + "/members/" + member.id() + "/role")).contentType("application/json")
				.content("{\"role\":\"MEMBER\"}")).andExpect(status().isNoContent());
		mvc.perform(as(admin, delete("/api/organizations/" + orgId + "/members/" + member.id()))).andExpect(status().isNoContent());
		mvc.perform(as(member, get("/api/organizations/" + orgId))).andExpect(status().isNotFound());
	}

	@Test
	void memberCanLeave_ownerCannot() throws Exception {
		TestUser owner = newUser();
		TestUser member = newUser();
		String orgId = createOrg(owner, "Sala Uno");
		join(orgId, owner, member, "MEMBER");

		mvc.perform(as(owner, delete("/api/organizations/" + orgId + "/members/" + owner.id()))).andExpect(status().isConflict());
		mvc.perform(as(member, delete("/api/organizations/" + orgId + "/members/" + member.id()))).andExpect(status().isNoContent());
		mvc.perform(as(member, get("/api/organizations/" + orgId))).andExpect(status().isNotFound());
	}

	/** Criterio de terminado de la Etapa 1: dos organizaciones distintas no ven ni tocan datos una de la otra. */
	@Test
	void organizations_areIsolatedFromEachOther() throws Exception {
		TestUser alice = newUser();
		TestUser bob = newUser();
		String aliceOrg = createOrg(alice, "Org de Alice");
		String bobOrg = createOrg(bob, "Org de Bob");
		TestUser aliceMember = newUser();
		join(aliceOrg, alice, aliceMember, "MEMBER");
		String invite = "{\"email\":\"%s\",\"role\":\"MEMBER\"}".formatted(uniqueEmail());

		// Bob no ve la organización de Alice en su listado
		String bobList = mvc.perform(as(bob, get("/api/organizations"))).andReturn().getResponse().getContentAsString();
		assertThat(bobList).contains(bobOrg).doesNotContain(aliceOrg);

		// Cada operación sobre la organización ajena responde 404, como si no existiera
		mvc.perform(as(bob, get("/api/organizations/" + aliceOrg))).andExpect(status().isNotFound());
		mvc.perform(as(bob, get("/api/organizations/" + aliceOrg + "/members"))).andExpect(status().isNotFound());
		mvc.perform(as(bob, get("/api/organizations/" + aliceOrg + "/invitations"))).andExpect(status().isNotFound());
		mvc.perform(as(bob, post("/api/organizations/" + aliceOrg + "/invitations")).contentType("application/json").content(invite))
				.andExpect(status().isNotFound());
		mvc.perform(as(bob, put("/api/organizations/" + aliceOrg + "/members/" + aliceMember.id() + "/role")).contentType("application/json")
				.content("{\"role\":\"ADMIN\"}")).andExpect(status().isNotFound());
		mvc.perform(as(bob, delete("/api/organizations/" + aliceOrg + "/members/" + aliceMember.id()))).andExpect(status().isNotFound());

		// Y nada de lo anterior cambió los datos de Alice
		mvc.perform(as(alice, get("/api/organizations/" + aliceOrg + "/members"))).andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2));
		mvc.perform(as(alice, get("/api/organizations/" + aliceOrg + "/invitations"))).andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
	}
}
