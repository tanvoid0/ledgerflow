package io.ledgerflow.risk.application;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/** The model, loaded once at startup. probabilities[0][1] is the fraud class; label is unused, Rules applies its own threshold to the score. */
@Component
public class Scorer {

    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final String modelVersion;
    private final Timer timer;

    public Scorer(MeterRegistry meters) {
        try {
            var bytes = new ClassPathResource("model/risk-lr-v1.onnx").getContentAsByteArray();
            this.session = env.createSession(bytes, new OrtSession.SessionOptions());
            this.modelVersion = session.getMetadata().getCustomMetadata().get("version");
        } catch (IOException | OrtException e) {
            throw new IllegalStateException("could not load model/risk-lr-v1.onnx", e);
        }
        this.timer = Timer.builder("risk.score").publishPercentileHistogram().register(meters);
    }

    public String modelVersion() {
        return modelVersion;
    }

    public double score(FeatureWindow.Features f) {
        return timer.record(() -> {
            try (var input = OnnxTensor.createTensor(env, new float[][] {
                    {f.countLastMinute(), (float) f.amountZScore(), f.newBeneficiary() ? 1f : 0f}});
                 var result = session.run(Map.of("input", input))) {
                var probabilities = (float[][]) result.get("probabilities").orElseThrow().getValue();
                return (double) probabilities[0][1];
            } catch (OrtException e) {
                throw new IllegalStateException(e);
            }
        });
    }
}
