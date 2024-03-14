/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.action.agents;

import static org.opensearch.ml.common.CommonValue.ML_AGENT_INDEX;

import java.time.Instant;

import org.opensearch.OpenSearchException;
import org.opensearch.action.ActionRequest;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.HandledTransportAction;
import org.opensearch.client.Client;
import org.opensearch.common.inject.Inject;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.commons.authuser.User;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.ml.common.MLModel;
import org.opensearch.ml.common.agent.MLAgent;
import org.opensearch.ml.common.transport.agent.MLRegisterAgentAction;
import org.opensearch.ml.common.transport.agent.MLRegisterAgentRequest;
import org.opensearch.ml.common.transport.agent.MLRegisterAgentResponse;
import org.opensearch.ml.engine.indices.MLIndicesHandler;
import org.opensearch.ml.model.MLModelManager;
import org.opensearch.ml.utils.RestActionUtils;
import org.opensearch.tasks.Task;
import org.opensearch.transport.TransportService;

import lombok.extern.log4j.Log4j2;

@Log4j2
public class TransportRegisterAgentAction extends HandledTransportAction<ActionRequest, MLRegisterAgentResponse> {
    MLIndicesHandler mlIndicesHandler;
    Client client;
    MLModelManager mlModelManager;

    @Inject
    public TransportRegisterAgentAction(
        TransportService transportService,
        ActionFilters actionFilters,
        Client client,
        MLIndicesHandler mlIndicesHandler,
        MLModelManager mlModelManager
    ) {
        super(MLRegisterAgentAction.NAME, transportService, actionFilters, MLRegisterAgentRequest::new);
        this.client = client;
        this.mlIndicesHandler = mlIndicesHandler;
        this.mlModelManager = mlModelManager;
    }

    @Override
    protected void doExecute(Task task, ActionRequest request, ActionListener<MLRegisterAgentResponse> listener) {
        User user = RestActionUtils.getUserContext(client);// TODO: check access
        MLRegisterAgentRequest registerAgentRequest = MLRegisterAgentRequest.fromActionRequest(request);
        MLAgent mlAgent = registerAgentRequest.getMlAgent();
        registerAgent(mlAgent, listener);
    }

    private void registerAgent(MLAgent agent, ActionListener<MLRegisterAgentResponse> listener) {
        Instant now = Instant.now();
        if (agent.getLlm() != null && agent.getLlm().getModelId() != null) {
            final String llmId = agent.getLlm().getModelId();
            try (ThreadContext.StoredContext context = client.threadPool().getThreadContext().stashContext()) {
                String[] excludes = new String[] { MLModel.MODEL_CONTENT_FIELD, MLModel.OLD_MODEL_CONTENT_FIELD };
                mlModelManager.getModel(llmId, null, excludes, ActionListener.wrap(mlModel -> {
                    processModelAndRegisterAgent(agent, mlModel.getIsHidden(), now, listener, context);
                }, e -> {
                    log.error("Failed to retrieve the ML model with ID: " + llmId, e);
                    listener.onFailure(e);
                }));
            } catch (Exception e) {
                log.error("Failed to get the model information " + llmId, e);
                listener.onFailure(e);
            }
        }
    }

    private void processModelAndRegisterAgent(
        MLAgent agent,
        Boolean isHiddenModel,
        Instant now,
        ActionListener<MLRegisterAgentResponse> listener,
        ThreadContext.StoredContext context
    ) {
        MLAgent mlAgent = agent.toBuilder().createdTime(now).lastUpdateTime(now).isHidden(isHiddenModel).build();
        mlIndicesHandler.initMLAgentIndex(ActionListener.wrap(result -> {
            if (result) {
                try (context) { // Using try-with-resources to ensure 'context' is restored
                    IndexRequest indexRequest = new IndexRequest(ML_AGENT_INDEX);
                    XContentBuilder builder = XContentBuilder.builder(XContentType.JSON.xContent());
                    mlAgent.toXContent(builder, ToXContent.EMPTY_PARAMS);
                    indexRequest.source(builder);
                    client.index(indexRequest, ActionListener.runBefore(ActionListener.wrap(r -> {
                        listener.onResponse(new MLRegisterAgentResponse(r.getId()));
                    }, e -> {
                        log.error("Failed to index ML agent", e);
                        listener.onFailure(e);
                    }), context::restore));
                } catch (Exception e) {
                    log.error("Failed to index ML agent", e);
                    listener.onFailure(e);
                }
            } else {
                log.error("Failed to create ML agent index");
                listener.onFailure(new OpenSearchException("Failed to create ML agent index"));
            }
        }, e -> {
            log.error("Failed to create ML agent index", e);
            listener.onFailure(e);
        }));
    }

}
