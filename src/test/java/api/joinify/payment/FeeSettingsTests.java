package api.joinify.payment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import api.joinify.organization.FeeSettings;
import api.joinify.organization.FeeSettings.Mode;

class FeeSettingsTests {

	@Test
	void percentageOnly_roundsHalfUp() {
		FeeSettings fees = new FeeSettings(Mode.PASSED_TO_BUYER, 500, 0);

		assertThat(fees.feeFor(10_000)).isEqualTo(500);
		assertThat(fees.feeFor(10_010)).isEqualTo(501); // 500,5 -> 501
		assertThat(fees.feeFor(10_009)).isEqualTo(500); // 500,45 -> 500
		assertThat(fees.feeFor(1)).isZero(); // 0,05 -> 0
	}

	@Test
	void fixedAmountIsAddedToThePercentage() {
		FeeSettings fees = new FeeSettings(Mode.ABSORBED, 300, 100);

		assertThat(fees.feeFor(10_000)).isEqualTo(400);
	}

	@Test
	void freeOrdersPayNoFee_evenWithAFixedAmount() {
		FeeSettings fees = new FeeSettings(Mode.PASSED_TO_BUYER, 500, 200);

		assertThat(fees.feeFor(0)).isZero();
		assertThat(fees.feeFor(-5)).isZero();
	}

	@Test
	void zeroConfiguration_meansNoFee() {
		assertThat(new FeeSettings(Mode.PASSED_TO_BUYER, 0, 0).feeFor(99_999)).isZero();
	}
}
