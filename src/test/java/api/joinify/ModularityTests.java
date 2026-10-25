package api.joinify;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

	@Test
	void verifiesModularStructure() {
		ApplicationModules.of(JoinifyApplication.class).verify();
	}

}
