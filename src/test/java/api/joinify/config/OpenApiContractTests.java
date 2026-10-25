package api.joinify.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

/**
 * El frontend se genera desde /v3/api-docs. Dos clases con el mismo nombre simple en módulos distintos se pisan en
 * el esquema y producen tipos TypeScript incorrectos sin que nada falle: esta prueba protege el contrato.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = { "app.storage.dir=build/test-uploads", "app.payments.reconcile-interval-ms=3600000", "app.tickets.repair-interval-ms=3600000" })
class OpenApiContractTests {

	@Autowired
	MockMvc mvc;

	private String docs() throws Exception {
		return mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	@Test
	void sameNamedDtosOfDifferentModules_keepTheirOwnSchema() throws Exception {
		String docs = docs();

		// Eventos: el lugar tiene id, país y aforo
		assertThat(JsonPath.<Object>read(docs, "$.components.schemas.VenueResponse.properties.id")).isNotNull();
		assertThat(JsonPath.<Object>read(docs, "$.components.schemas.VenueResponse.properties.country")).isNotNull();
		// Boletos: su propio esquema, sin esos campos
		assertThat(JsonPath.<java.util.Map<String, Object>>read(docs, "$.components.schemas.TicketVenueResponse.properties")).doesNotContainKey("id");
		assertThat(JsonPath.<Object>read(docs, "$.components.schemas.TicketSessionResponse.properties.venue.$ref").toString()).endsWith("TicketVenueResponse");
		assertThat(JsonPath.<Object>read(docs, "$.components.schemas.SessionResponse.properties.venue.$ref").toString()).endsWith("/VenueResponse");
	}

	@Test
	void everyOperationHasAUniqueReadableId() throws Exception {
		java.util.List<String> ids = JsonPath.read(docs(), "$.paths.*.*.operationId");

		assertThat(ids).doesNotHaveDuplicates();
		assertThat(ids).noneMatch(id -> id.matches(".*_[0-9]+$"));
	}
}
