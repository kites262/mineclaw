package cc.kites.mineclaw.turn;

import cc.kites.mineclaw.api.ApiMessage;
import cc.kites.mineclaw.api.ApiUsage;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextTokenEstimatorTest {
    @Test
    void fallsBackLocallyAndThenCalibratesFromRealPromptUsage() {
        ContextTokenEstimator estimator = new ContextTokenEstimator();
        List<ApiMessage> messages = List.of(ApiMessage.user("你好 world"));
        ContextTokenEstimator.Estimate local = estimator.estimate("provider/model", "system",
                messages, List.of(new JsonObject()));

        assertThat(local.providerCalibrated()).isFalse();
        assertThat(local.tokens()).isEqualTo(local.rawTokens());

        int actual = local.rawTokens() * 2;
        estimator.observe("provider/model", local.rawTokens(), new ApiUsage(actual, 20, actual + 20));
        ContextTokenEstimator.Estimate calibrated = estimator.estimate("provider/model", "system",
                messages, List.of(new JsonObject()));

        assertThat(calibrated.providerCalibrated()).isTrue();
        assertThat(calibrated.tokens()).isEqualTo(actual);
    }

    @Test
    void incompleteUsageUsesTotalWithoutAddingNestedDetails() {
        ContextTokenEstimator estimator = new ContextTokenEstimator();
        ContextTokenEstimator.Estimate local = estimator.estimate("provider/model", "system",
                List.of(ApiMessage.user("data")), List.of());

        int actual = local.rawTokens() * 3;
        estimator.observe("provider/model", local.rawTokens(), new ApiUsage(null, null, actual));

        assertThat(estimator.estimate("provider/model", "system",
                List.of(ApiMessage.user("data")), List.of()).tokens()).isEqualTo(actual);
    }

    @Test
    void playerIdentityFlagsMatchSerializedPromptRepresentations() {
        List<ApiMessage> messages = List.of(ApiMessage.user("Alice", "hello"));

        int neither = ContextTokenEstimator.rawEstimate("system", messages, List.of(), false, false);
        int nameOnly = ContextTokenEstimator.rawEstimate("system", messages, List.of(), true, false);
        int envelopeOnly = ContextTokenEstimator.rawEstimate("system", messages, List.of(), false, true);
        int both = ContextTokenEstimator.rawEstimate("system", messages, List.of(), true, true);

        assertThat(nameOnly).isGreaterThan(neither);
        assertThat(envelopeOnly).isGreaterThan(neither);
        assertThat(both).isGreaterThan(nameOnly).isGreaterThan(envelopeOnly);
    }
}
