package org.opensearch.ml.engine.algorithms.sample;

import org.opensearch.ml.common.dataframe.DataFrame;
import org.opensearch.ml.common.parameter.MLAlgoName;
import org.opensearch.ml.common.parameter.MLOutput;
import org.opensearch.ml.common.parameter.SampleAlgoOutput;
import org.opensearch.ml.common.parameter.SampleAlgoParams;
import org.opensearch.ml.engine.MLAlgo;
import org.opensearch.ml.engine.MLAlgoMetaData;
import org.opensearch.ml.engine.Model;
import org.opensearch.ml.engine.annotation.MLAlgorithm;
import org.opensearch.ml.engine.utils.ModelSerDeSer;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@MLAlgorithm("sample_algo")
public class SampleAlgo implements MLAlgo {
    private static final int DEFAULT_SAMPLE_PARAM = -1;
    private int sampleParam;

    public SampleAlgo(){}

    public SampleAlgo(SampleAlgoParams parameters) {
        this.sampleParam = Optional.ofNullable(parameters.getSampleParam()).orElse(DEFAULT_SAMPLE_PARAM);
    }

    @Override
    public MLOutput predict(DataFrame dataFrame, Model model) {
        if (model == null) {
            throw new IllegalArgumentException("No model found for KMeans prediction.");
        }
        AtomicReference<Double> sum = new AtomicReference<>((double) 0);
        dataFrame.forEach(row -> {
            row.forEach(item -> sum.updateAndGet(v -> v + item.doubleValue()));
        });
        return SampleAlgoOutput.builder().sampleResult(sum.get()).build();
    }

    @Override
    public Model train(DataFrame dataFrame) {
        Model model = new Model();
        model.setName("SampleAlgo");
        model.setVersion(1);
        model.setContent(ModelSerDeSer.serialize("This is a sample testing model with parameter: " + sampleParam));
        return model;
    }

    @Override
    public MLAlgoMetaData getMetaData() {
        return MLAlgoMetaData.builder().name(MLAlgoName.LOCAL_SAMPLE_CALCULATOR.name())
                .description("A sample algorithm.")
                .version("1.0")
                .predictable(true)
                .trainable(true)
                .build();
    }
}
