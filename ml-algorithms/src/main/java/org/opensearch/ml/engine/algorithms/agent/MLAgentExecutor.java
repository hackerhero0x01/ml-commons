/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.engine.algorithms.agent;

import static org.opensearch.core.action.ActionListener.wrap;
import static org.opensearch.core.xcontent.XContentParserUtils.ensureExpectedToken;
import static org.opensearch.ml.common.CommonValue.ML_AGENT_INDEX;

import java.io.IOException;
import java.security.AccessController;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.opensearch.ResourceNotFoundException;
import org.opensearch.action.get.GetRequest;
import org.opensearch.action.get.GetResponse;
import org.opensearch.action.support.PlainActionFuture;
import org.opensearch.client.Client;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.common.xcontent.LoggingDeprecationHandler;
import org.opensearch.common.xcontent.XContentHelper;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.common.bytes.BytesReference;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.ml.common.FunctionName;
import org.opensearch.ml.common.agent.MLAgent;
import org.opensearch.ml.common.agent.MLMemorySpec;
import org.opensearch.ml.common.dataset.remote.RemoteInferenceInputDataSet;
import org.opensearch.ml.common.exception.MLException;
import org.opensearch.ml.common.exception.MLValidationException;
import org.opensearch.ml.common.input.Input;
import org.opensearch.ml.common.input.execute.agent.AgentMLInput;
import org.opensearch.ml.common.output.Output;
import org.opensearch.ml.common.output.model.ModelTensor;
import org.opensearch.ml.common.output.model.ModelTensorOutput;
import org.opensearch.ml.common.output.model.ModelTensors;
import org.opensearch.ml.common.spi.memory.Memory;
import org.opensearch.ml.common.spi.tools.Tool;
import org.opensearch.ml.engine.Executable;
import org.opensearch.ml.engine.annotation.Function;
import org.opensearch.ml.engine.memory.ConversationIndexMemory;
import org.opensearch.ml.engine.memory.ConversationIndexMessage;
import org.opensearch.ml.memory.action.conversation.CreateInteractionResponse;
import org.opensearch.ml.memory.action.conversation.GetInteractionAction;
import org.opensearch.ml.memory.action.conversation.GetInteractionRequest;
import org.opensearch.ml.memory.action.conversation.GetInteractionResponse;

import com.google.gson.Gson;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.log4j.Log4j2;

@Log4j2
@Data
@NoArgsConstructor
@Function(FunctionName.AGENT)
public class MLAgentExecutor implements Executable {

    public static final String MEMORY_ID = "memory_id";
    public static final String QUESTION = "question";
    public static final String PARENT_INTERACTION_ID = "parent_interaction_id";
    public static final String REGENERATE_INTERACTION_ID = "regenerate_interaction_id";

    private Client client;
    private Settings settings;
    private ClusterService clusterService;
    private NamedXContentRegistry xContentRegistry;
    private Map<String, Tool.Factory> toolFactories;
    private Map<String, Memory.Factory> memoryFactoryMap;

    public MLAgentExecutor(
        Client client,
        Settings settings,
        ClusterService clusterService,
        NamedXContentRegistry xContentRegistry,
        Map<String, Tool.Factory> toolFactories,
        Map<String, Memory.Factory> memoryFactoryMap
    ) {
        this.client = client;
        this.settings = settings;
        this.clusterService = clusterService;
        this.xContentRegistry = xContentRegistry;
        this.toolFactories = toolFactories;
        this.memoryFactoryMap = memoryFactoryMap;
    }

    @Override
    public void execute(Input input, ActionListener<Output> listener) {
        if (input == null || !(input instanceof AgentMLInput)) {
            throw new IllegalArgumentException("wrong input");
        }
        AgentMLInput agentMLInput = (AgentMLInput) input;
        String agentId = agentMLInput.getAgentId();
        RemoteInferenceInputDataSet inputDataSet = (RemoteInferenceInputDataSet) agentMLInput.getInputDataset();
        if (inputDataSet.getParameters() == null) {
            throw new IllegalArgumentException("wrong input");
        }

        List<ModelTensors> outputs = new ArrayList<>();
        List<ModelTensor> modelTensors = new ArrayList<>();
        outputs.add(ModelTensors.builder().mlModelTensors(modelTensors).build());

        if (clusterService.state().metadata().hasIndex(ML_AGENT_INDEX)) {
            try (ThreadContext.StoredContext context = client.threadPool().getThreadContext().stashContext()) {
                GetRequest getRequest = new GetRequest(ML_AGENT_INDEX).id(agentId);
                client.get(getRequest, ActionListener.runBefore(ActionListener.<GetResponse>wrap(r -> {
                    if (r.isExists()) {
                        try (XContentParser parser = createXContentParserFromRegistry(xContentRegistry, r.getSourceAsBytesRef())) {
                            ensureExpectedToken(XContentParser.Token.START_OBJECT, parser.nextToken(), parser);
                            MLAgent mlAgent = MLAgent.parse(parser);
                            MLMemorySpec memorySpec = mlAgent.getMemory();
                            String memoryId = inputDataSet.getParameters().get(MEMORY_ID);
                            String parentInteractionId = inputDataSet.getParameters().get(PARENT_INTERACTION_ID);
                            String regenerateInteractionId = inputDataSet.getParameters().get(REGENERATE_INTERACTION_ID);
                            String appType = mlAgent.getAppType();
                            String question = inputDataSet.getParameters().get(QUESTION);

                            if (memoryId == null && regenerateInteractionId != null) {
                                listener.onFailure(new MLValidationException("Memory id must provide for regenerate"));
                            }

                            if (memorySpec != null
                                && memorySpec.getType() != null
                                && memoryFactoryMap.containsKey(memorySpec.getType())
                                && (memoryId == null || parentInteractionId == null)) {
                                ConversationIndexMemory.Factory conversationIndexMemoryFactory =
                                    (ConversationIndexMemory.Factory) memoryFactoryMap.get(memorySpec.getType());
                                conversationIndexMemoryFactory.create(question, memoryId, appType, wrap(memory -> {
                                    inputDataSet.getParameters().put(MEMORY_ID, memory.getConversationId());

                                    // get regenerate interaction question
                                    Optional.ofNullable(regenerateInteractionId).ifPresent(interactionId -> {
                                        log.info("Regenerate for existing interaction {}", regenerateInteractionId);
                                        getQuestionFromInteraction(memoryId, interactionId, inputDataSet);
                                    });

                                    // Create root interaction ID
                                    ConversationIndexMessage msg = ConversationIndexMessage
                                        .conversationIndexMessageBuilder()
                                        .type(appType)
                                        .question(inputDataSet.getParameters().get(QUESTION))
                                        .response("")
                                        .finalAnswer(true)
                                        .sessionId(memory.getConversationId())
                                        .build();
                                    memory.save(msg, null, null, null, ActionListener.<CreateInteractionResponse>wrap(interaction -> {
                                        log.info("Created parent interaction ID: " + interaction.getId());
                                        inputDataSet.getParameters().put(PARENT_INTERACTION_ID, interaction.getId());
                                        ActionListener<Object> agentActionListener = createAgentActionListener(
                                            listener,
                                            outputs,
                                            modelTensors
                                        );
                                        executeAgent(inputDataSet, mlAgent, agentActionListener);
                                    }, ex -> {
                                        log.error("Failed to create parent interaction", ex);
                                        listener.onFailure(ex);
                                    }));
                                }, ex -> {
                                    log.error("Failed to read conversation memory", ex);
                                    listener.onFailure(ex);
                                }));
                            } else {
                                ActionListener<Object> agentActionListener = createAgentActionListener(listener, outputs, modelTensors);
                                executeAgent(inputDataSet, mlAgent, agentActionListener);
                            }
                        } catch (Exception ex) {
                            listener.onFailure(ex);
                        }
                    } else {
                        listener.onFailure(new ResourceNotFoundException("Agent not found"));
                    }
                }, e -> {
                    log.error("Failed to get agent", e);
                    listener.onFailure(e);
                }), () -> context.restore()));
            }
        }

    }

    /**
     * Get question from existing interaction
     * @param memoryId conversation id
     * @param interactionId interaction id
     * @param inputDataSet input parameters
     */
    private void getQuestionFromInteraction(String memoryId, String interactionId, RemoteInferenceInputDataSet inputDataSet) {
        PlainActionFuture<GetInteractionResponse> future = PlainActionFuture.newFuture();
        client.execute(GetInteractionAction.INSTANCE, new GetInteractionRequest(memoryId, interactionId), future);
        try {
            GetInteractionResponse interactionResponse = future.get();
            inputDataSet.getParameters().computeIfAbsent(QUESTION, key -> interactionResponse.getInteraction().getInput());
        } catch (Exception ex) {
            log.error("Can't get regenerate interaction {}", interactionId, ex);
            throw new MLException(ex);
        }
    }

    private void executeAgent(RemoteInferenceInputDataSet inputDataSet, MLAgent mlAgent, ActionListener<Object> agentActionListener) {
        if ("flow".equals(mlAgent.getType())) {
            MLFlowAgentRunner flowAgentExecutor = new MLFlowAgentRunner(
                client,
                settings,
                clusterService,
                xContentRegistry,
                toolFactories,
                memoryFactoryMap
            );
            flowAgentExecutor.run(mlAgent, inputDataSet.getParameters(), agentActionListener);
        } else if ("cot".equals(mlAgent.getType())) {
            MLReActAgentRunner reactAgentExecutor = new MLReActAgentRunner(
                client,
                settings,
                clusterService,
                xContentRegistry,
                toolFactories,
                memoryFactoryMap
            );
            reactAgentExecutor.run(mlAgent, inputDataSet.getParameters(), agentActionListener);
        } else if ("conversational".equals(mlAgent.getType())) {
            MLChatAgentRunner chatAgentRunner = new MLChatAgentRunner(
                client,
                settings,
                clusterService,
                xContentRegistry,
                toolFactories,
                memoryFactoryMap
            );
            chatAgentRunner.run(mlAgent, inputDataSet.getParameters(), agentActionListener);
        }
    }

    private ActionListener<Object> createAgentActionListener(
        ActionListener<Output> listener,
        List<ModelTensors> outputs,
        List<ModelTensor> modelTensors
    ) {
        return wrap(output -> {
            if (output != null) {
                Gson gson = new Gson();
                if (output instanceof ModelTensorOutput) {
                    ModelTensorOutput modelTensorOutput = (ModelTensorOutput) output;
                    modelTensorOutput.getMlModelOutputs().forEach(outs -> {
                        for (ModelTensor mlModelTensor : outs.getMlModelTensors()) {
                            modelTensors.add(mlModelTensor);
                        }
                    });
                } else if (output instanceof ModelTensor) {
                    modelTensors.add((ModelTensor) output);
                } else if (output instanceof List) {
                    if (((List) output).get(0) instanceof ModelTensor) {
                        ((List<ModelTensor>) output).forEach(mlModelTensor -> modelTensors.add(mlModelTensor));
                    } else if (((List) output).get(0) instanceof ModelTensors) {
                        ((List<ModelTensors>) output).forEach(outs -> {
                            for (ModelTensor mlModelTensor : outs.getMlModelTensors()) {
                                modelTensors.add(mlModelTensor);
                            }
                        });
                    } else {
                        Object finalOutput = output;
                        String result = output instanceof String
                            ? (String) output
                            : AccessController.doPrivileged((PrivilegedExceptionAction<String>) () -> gson.toJson(finalOutput));
                        modelTensors.add(ModelTensor.builder().name("response").result(result).build());
                    }
                } else {
                    Object finalOutput = output;
                    String result = output instanceof String
                        ? (String) output
                        : AccessController.doPrivileged((PrivilegedExceptionAction<String>) () -> gson.toJson(finalOutput));
                    modelTensors.add(ModelTensor.builder().name("response").result(result).build());
                }
                listener.onResponse(ModelTensorOutput.builder().mlModelOutputs(outputs).build());
            } else {
                listener.onResponse(null);
            }
        }, ex -> {
            log.error("Failed to run flow agent", ex);
            listener.onFailure(ex);
        });
    }

    public XContentParser createXContentParserFromRegistry(NamedXContentRegistry xContentRegistry, BytesReference bytesReference)
        throws IOException {
        return XContentHelper.createParser(xContentRegistry, LoggingDeprecationHandler.INSTANCE, bytesReference, XContentType.JSON);
    }
}
