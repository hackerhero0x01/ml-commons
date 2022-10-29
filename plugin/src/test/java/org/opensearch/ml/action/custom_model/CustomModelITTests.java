/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.action.custom_model;

import static org.opensearch.ml.utils.TestData.SENTENCE_TRANSFORMER_MODEL_URL;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Before;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.ml.action.MLCommonsIntegTestCase;
import org.opensearch.ml.action.profile.MLProfileNodeResponse;
import org.opensearch.ml.action.profile.MLProfileResponse;
import org.opensearch.ml.common.FunctionName;
import org.opensearch.ml.common.MLModel;
import org.opensearch.ml.common.MLTaskState;
import org.opensearch.ml.common.MLTaskType;
import org.opensearch.ml.common.dataset.TextDocsInputDataSet;
import org.opensearch.ml.common.model.MLModelFormat;
import org.opensearch.ml.common.model.MLModelState;
import org.opensearch.ml.common.model.TextEmbeddingModelConfig;
import org.opensearch.ml.common.output.model.ModelTensorOutput;
import org.opensearch.ml.common.transport.MLTaskResponse;
import org.opensearch.ml.common.transport.unload.UnloadModelNodesResponse;
import org.opensearch.test.OpenSearchIntegTestCase;

import com.google.common.collect.ImmutableSet;

@OpenSearchIntegTestCase.ClusterScope(scope = OpenSearchIntegTestCase.Scope.SUITE, numDataNodes = 1)
public class CustomModelITTests extends MLCommonsIntegTestCase {

    @Before
    public void setUp() throws Exception {
        super.setUp();
    }

    public void testCustomModelWorkflow() throws InterruptedException {
        FunctionName functionName = FunctionName.TEXT_EMBEDDING;
        String modelName = "small-model";
        String version = "1.0.0";
        MLModelFormat modelFormat = MLModelFormat.TORCH_SCRIPT;
        String modelType = "bert";
        TextEmbeddingModelConfig.FrameworkType frameworkType = TextEmbeddingModelConfig.FrameworkType.SENTENCE_TRANSFORMERS;
        int dimension = 768;
        String allConfig = null;

        // upload model
        String taskId = uploadModel(
            functionName,
            modelName,
            version,
            modelFormat,
            modelType,
            frameworkType,
            dimension,
            allConfig,
            SENTENCE_TRANSFORMER_MODEL_URL,
            false
        );
        assertNotNull(taskId);

        // profile all
        MLProfileResponse allProfileAfterUploading = getAllProfile();
        verifyRunningTask(taskId, MLTaskType.UPLOAD_MODEL, ImmutableSet.of(MLTaskState.RUNNING), allProfileAfterUploading);

        AtomicReference<String> modelId = new AtomicReference<>();
        AtomicReference<MLModel> mlModel = new AtomicReference<>();
        waitUntil(() -> {
            String id = getTask(taskId).getModelId();
            modelId.set(id);
            MLModel model = null;
            try {
                if (id != null) {
                    model = getModel(id + "_" + 0);
                    mlModel.set(model);
                }
            } catch (Exception e) {
                logger.error("Failed to get model " + id, e);
            }
            return modelId.get() != null;
        }, 20, TimeUnit.SECONDS);
        assertNotNull(modelId.get());
        MLModel model = getModel(modelId.get());
        assertNotNull(model);
        assertEquals(1, model.getTotalChunks().intValue());
        assertNotNull(mlModel.get());
        assertEquals(0, mlModel.get().getChunkNumber().intValue());

        waitUntil(() -> {
            SearchResponse response = searchModelChunks(modelId.get());
            AtomicBoolean modelChunksReady = new AtomicBoolean(false);
            if (response != null) {
                long totalHits = response.getHits().getTotalHits().value;
                if (totalHits == 9) {
                    modelChunksReady.set(true);
                }
            }
            return modelChunksReady.get();
        }, 20, TimeUnit.SECONDS);

        // load model
        String loadTaskId = loadModel(modelId.get());

        // profile all
        MLProfileResponse allProfileAfterLoading = getAllProfile();
        // Thread.sleep(10);
        verifyRunningTask(
            loadTaskId,
            MLTaskType.LOAD_MODEL,
            ImmutableSet.of(MLTaskState.CREATED, MLTaskState.RUNNING),
            allProfileAfterLoading
        );

        AtomicBoolean loaded = new AtomicBoolean(false);
        waitUntil(() -> {
            MLProfileResponse modelProfile = getModelProfile(taskId);
            if (modelProfile != null) {
                List<MLProfileNodeResponse> nodes = modelProfile.getNodes();
                if (nodes != null) {
                    nodes.forEach(node -> {
                        node.getMlNodeModels().entrySet().forEach(e -> {
                            if (e.getValue().getModelState() == MLModelState.LOADED) {
                                loaded.set(true);
                            }
                        });
                    });
                }
            }
            return loaded.get();
        }, 20, TimeUnit.SECONDS);
        assertTrue(loaded.get());

        // profile model
        MLProfileResponse modelProfile = getModelProfile(modelId.get());
        verifyNoRunningTask(modelProfile);
        verifyLoadedModel(modelId.get(), 0, modelProfile);

        // predict
        MLTaskResponse response = predict(
            modelId.get(),
            functionName,
            TextDocsInputDataSet.builder().docs(Arrays.asList("today is sunny")).build(),
            null
        );

        MLProfileResponse allProfile = getAllProfile();
        verifyNoRunningTask(allProfile);
        verifyLoadedModel(modelId.get(), 1, allProfile);

        ModelTensorOutput output = (ModelTensorOutput) response.getOutput();
        assertEquals(1, output.getMlModelOutputs().size());
        assertEquals(1, output.getMlModelOutputs().get(0).getMlModelTensors().size());
        assertEquals(dimension, output.getMlModelOutputs().get(0).getMlModelTensors().get(0).getData().length);

        // unload model
        UnloadModelNodesResponse unloadModelResponse = unloadModel(modelId.get());
        assertEquals(1, unloadModelResponse.getNodes().size());
        Map<String, String> unloadStatus = unloadModelResponse.getNodes().get(0).getModelUnloadStatus();
        assertEquals(1, unloadStatus.size());
        assertEquals("unloaded", unloadStatus.get(modelId.get()));
    }

    private void verifyRunningTask(
        String taskId,
        MLTaskType taskType,
        Set<MLTaskState> states,
        MLProfileResponse allProfileAfterUploading
    ) {
        for (MLProfileNodeResponse nodeResponse : allProfileAfterUploading.getNodes()) {
            if (nodeResponse.getNode().isDataNode()) {
                if (nodeResponse.getMlNodeTasks().containsKey(taskId)) {
                    assertTrue(states.contains(nodeResponse.getMlNodeTasks().get(taskId).getState()));
                    assertEquals(taskType, nodeResponse.getMlNodeTasks().get(taskId).getTaskType());
                }
            }
        }
    }

    private void verifyNoRunningTask(MLProfileResponse allProfileAfterUploading) {
        for (MLProfileNodeResponse nodeResponse : allProfileAfterUploading.getNodes()) {
            if (nodeResponse.getNode().isDataNode()) {
                assertEquals(0, nodeResponse.getMlNodeTasks().size());
            }
        }
    }

    private void verifyLoadedModel(String modelId, long predictCounts, MLProfileResponse allProfileAfterUploading) {
        for (MLProfileNodeResponse nodeResponse : allProfileAfterUploading.getNodes()) {
            if (nodeResponse.getNode().isDataNode()) {
                assertTrue(nodeResponse.getMlNodeModels().containsKey(modelId));
                assertEquals(MLModelState.LOADED, nodeResponse.getMlNodeModels().get(modelId).getModelState());
                if (predictCounts == 0) {
                    assertNull(nodeResponse.getMlNodeModels().get(modelId).getPredictStats());
                }
            }
        }
    }
}
