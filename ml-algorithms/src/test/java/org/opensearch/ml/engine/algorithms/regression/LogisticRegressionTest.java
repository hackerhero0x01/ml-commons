/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.engine.algorithms.regression;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.opensearch.ml.common.FunctionName;
import org.opensearch.ml.common.Model;
import org.opensearch.ml.common.dataframe.DataFrame;
import org.opensearch.ml.common.input.parameter.regression.LogisticRegressionParams;
import org.opensearch.ml.common.output.MLPredictionOutput;

import static org.opensearch.ml.engine.helper.LogisticRegressionHelper.constructLogisticRegressionPredictionDataFrame;
import static org.opensearch.ml.engine.helper.LogisticRegressionHelper.constructLogisticRegressionTrainDataFrame;

public class LogisticRegressionTest {
    @Rule
    public ExpectedException exceptionRule = ExpectedException.none();

    private LogisticRegressionParams parameters;
    private DataFrame trainDataFrame;
    private DataFrame predictionDataFrame;

    @Before
    public void setUp() {
        parameters = LogisticRegressionParams.builder()
                .objectiveType(LogisticRegressionParams.ObjectiveType.LOGMULTICLASS)
                .optimizerType(LogisticRegressionParams.OptimizerType.ADA_GRAD)
                .learningRate(0.9)
                .epsilon(1e-6)
                .target("class")
                .build();
        trainDataFrame = constructLogisticRegressionTrainDataFrame();
        predictionDataFrame = constructLogisticRegressionPredictionDataFrame();
    }

    @Test
    public void train() {
        trainAndVerify(parameters);
    }

    @Test
    public void trainExceptionWithoutTarget() {
        exceptionRule.expect(RuntimeException.class);
        exceptionRule.expectMessage("Empty target when generating dataset from data frame.");
        parameters.setTarget(null);
        LogisticRegression classification = new LogisticRegression(parameters);
        Model model = classification.train(trainDataFrame);
    }

    @Test
    public void predict() {
        LogisticRegression classification = new LogisticRegression(parameters);
        Model model = classification.train(trainDataFrame);
        MLPredictionOutput output = (MLPredictionOutput)classification.predict(predictionDataFrame, model);
        DataFrame predictions = output.getPredictionResult();
        Assert.assertEquals(2, predictions.size());
    }

    @Test
    public void predictWithoutModel() {
        exceptionRule.expect(IllegalArgumentException.class);
        exceptionRule.expectMessage("No model found for logistic regression prediction.");
        LogisticRegression classification = new LogisticRegression(parameters);
        classification.predict(predictionDataFrame, null);
    }

    @Test
    public void constructorNegativeLearnRate() {
        exceptionRule.expect(RuntimeException.class);
        exceptionRule.expectMessage("Learning rate should not be negative");
        new LogisticRegression(parameters.toBuilder().learningRate(-0.1).build());
    }

    @Test
    public void constructorNegativeEpsilon() {
        exceptionRule.expect(RuntimeException.class);
        exceptionRule.expectMessage("Epsilon should not be negative");
        new LogisticRegression(parameters.toBuilder().epsilon(-1.0).build());
    }

    @Test
    public void constructorNegativeEpochs() {
        exceptionRule.expect(RuntimeException.class);
        exceptionRule.expectMessage("Epochs should not be negative");
        new LogisticRegression(parameters.toBuilder().epochs(-1).build());
    }

    @Test
    public void constructorNegativeBatchSize() {
        exceptionRule.expect(RuntimeException.class);
        exceptionRule.expectMessage("MiniBatchSize should not be negative");
        new LogisticRegression(parameters.toBuilder().batchSize(-1).build());
    }

    private void trainAndVerify(LogisticRegressionParams params) {
        LogisticRegression classification = new LogisticRegression(params);
        Model model = classification.train(trainDataFrame);
        Assert.assertEquals(FunctionName.LOGISTIC_REGRESSION.name(), model.getName());
        Assert.assertEquals(1, model.getVersion());
        Assert.assertNotNull(model.getContent());
    }
}
