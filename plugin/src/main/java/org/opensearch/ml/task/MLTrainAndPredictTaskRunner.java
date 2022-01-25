/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 *
 */

package org.opensearch.ml.task;

import static org.opensearch.ml.plugin.MachineLearningPlugin.TASK_THREAD_POOL;
import static org.opensearch.ml.stats.StatNames.ML_EXECUTING_TASK_COUNT;

import java.time.Instant;
import java.util.UUID;

import lombok.extern.log4j.Log4j2;

import org.opensearch.action.ActionListener;
import org.opensearch.action.ActionListenerResponseHandler;
import org.opensearch.action.support.ThreadedActionListener;
import org.opensearch.client.Client;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.ml.common.dataframe.DataFrame;
import org.opensearch.ml.common.dataset.DataFrameInputDataset;
import org.opensearch.ml.common.dataset.MLInputDataType;
import org.opensearch.ml.common.parameter.MLInput;
import org.opensearch.ml.common.parameter.MLOutput;
import org.opensearch.ml.common.parameter.MLPredictionOutput;
import org.opensearch.ml.common.transport.MLTaskResponse;
import org.opensearch.ml.common.transport.training.MLTrainingTaskRequest;
import org.opensearch.ml.common.transport.trainpredict.MLTrainAndPredictionTaskAction;
import org.opensearch.ml.engine.MLEngine;
import org.opensearch.ml.indices.MLInputDatasetHandler;
import org.opensearch.ml.model.MLTask;
import org.opensearch.ml.model.MLTaskState;
import org.opensearch.ml.model.MLTaskType;
import org.opensearch.ml.stats.MLStats;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.TransportService;

/**
 * MLPredictTaskRunner is responsible for running predict tasks.
 */
@Log4j2
public class MLTrainAndPredictTaskRunner extends MLTaskRunner<MLTrainingTaskRequest, MLTaskResponse> {
    private final ThreadPool threadPool;
    private final ClusterService clusterService;
    private final Client client;
    private final MLInputDatasetHandler mlInputDatasetHandler;

    public MLTrainAndPredictTaskRunner(
        ThreadPool threadPool,
        ClusterService clusterService,
        Client client,
        MLTaskManager mlTaskManager,
        MLStats mlStats,
        MLInputDatasetHandler mlInputDatasetHandler,
        MLTaskDispatcher mlTaskDispatcher
    ) {
        super(mlTaskManager, mlStats, mlTaskDispatcher);
        this.threadPool = threadPool;
        this.clusterService = clusterService;
        this.client = client;
        this.mlInputDatasetHandler = mlInputDatasetHandler;
    }

    @Override
    public void run(MLTrainingTaskRequest request, TransportService transportService, ActionListener<MLTaskResponse> listener) {
        mlTaskDispatcher.dispatchTask(ActionListener.wrap(node -> {
            if (clusterService.localNode().getId().equals(node.getId())) {
                // Execute prediction task locally
                log.info("execute ML train and prediction request {} locally on node {}", request.toString(), node.getId());
                startTrainAndPredictionTask(request, listener);
            } else {
                // Execute batch task remotely
                log.info("execute ML train and prediction request {} remotely on node {}", request.toString(), node.getId());
                transportService
                    .sendRequest(
                        node,
                        MLTrainAndPredictionTaskAction.NAME,
                        request,
                        new ActionListenerResponseHandler<>(listener, MLTaskResponse::new)
                    );
            }
        }, e -> listener.onFailure(e)));
    }

    /**
     * Start prediction task
     * @param request MLPredictionTaskRequest
     * @param listener Action listener
     */
    public void startTrainAndPredictionTask(MLTrainingTaskRequest request, ActionListener<MLTaskResponse> listener) {
        MLInputDataType inputDataType = request.getMlInput().getInputDataset().getInputDataType();
        Instant now = Instant.now();
        MLTask mlTask = MLTask
            .builder()
            .taskId(UUID.randomUUID().toString())
            .taskType(MLTaskType.TRAINING_AND_PREDICTION)
            .inputType(inputDataType)
            .functionName(request.getMlInput().getFunctionName())
            .state(MLTaskState.CREATED)
            .workerNode(clusterService.localNode().getId())
            .createTime(now)
            .lastUpdateTime(now)
            .async(false)
            .build();
        MLInput mlInput = request.getMlInput();

        if (mlInput.getInputDataset().getInputDataType().equals(MLInputDataType.SEARCH_QUERY)) {
            ActionListener<DataFrame> dataFrameActionListener = ActionListener
                .wrap(dataFrame -> { trainAndPredict(mlTask, dataFrame, request, listener); }, e -> {
                    log.error("Failed to generate DataFrame from search query", e);
                    mlTaskManager.addIfAbsent(mlTask);
                    handleMLTaskFailure(mlTask, e);
                    listener.onFailure(e);
                });
            mlInputDatasetHandler
                .parseSearchQueryInput(
                    mlInput.getInputDataset(),
                    new ThreadedActionListener<>(log, threadPool, TASK_THREAD_POOL, dataFrameActionListener, false)
                );
        } else {
            DataFrame inputDataFrame = mlInputDatasetHandler.parseDataFrameInput(mlInput.getInputDataset());
            threadPool.executor(TASK_THREAD_POOL).execute(() -> { trainAndPredict(mlTask, inputDataFrame, request, listener); });
        }
    }

    private void trainAndPredict(
        MLTask mlTask,
        DataFrame inputDataFrame,
        MLTrainingTaskRequest request,
        ActionListener<MLTaskResponse> listener
    ) {
        // track ML task count and add ML task into cache
        mlStats.getStat(ML_EXECUTING_TASK_COUNT.getName()).increment();
        mlTaskManager.add(mlTask);

        MLInput mlInput = request.getMlInput();

        mlTaskManager.updateTaskState(mlTask.getTaskId(), MLTaskState.RUNNING, mlTask.isAsync());
        // run predict
        try {
            mlTaskManager.updateTaskState(mlTask.getTaskId(), MLTaskState.RUNNING, mlTask.isAsync());
            MLOutput output = MLEngine.trainAndPredict(mlInput.toBuilder().inputDataset(new DataFrameInputDataset(inputDataFrame)).build());
            if (output instanceof MLPredictionOutput) {
                ((MLPredictionOutput) output).setTaskId(mlTask.getTaskId());
                ((MLPredictionOutput) output).setStatus(mlTaskManager.get(mlTask.getTaskId()).getState().name());
            }

            // Once prediction complete, reduce ML_EXECUTING_TASK_COUNT and update task state
            handleMLTaskComplete(mlTask);
            MLTaskResponse response = MLTaskResponse.builder().output(output).build();
            log.info("Train and predict task done for algorithm: {}, task id: {}", mlTask.getFunctionName(), mlTask.getTaskId());
            listener.onResponse(response);
        } catch (Exception e) {
            // todo need to specify what exception
            log.error("Failed to train and predict " + mlInput.getAlgorithm(), e);
            handlePredictFailure(mlTask, listener, e);
            return;
        }
    }

    private void handlePredictFailure(MLTask mlTask, ActionListener<MLTaskResponse> listener, Exception e) {
        handleMLTaskFailure(mlTask, e);
        listener.onFailure(e);
    }
}
