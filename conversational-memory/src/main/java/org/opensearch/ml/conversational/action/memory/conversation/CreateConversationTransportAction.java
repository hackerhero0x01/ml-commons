/*
 * Copyright 2023 Aryn
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.opensearch.ml.conversational.action.memory.conversation;

import org.opensearch.action.ActionListener;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.HandledTransportAction;
import org.opensearch.client.Client;
import org.opensearch.common.inject.Inject;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.ml.conversational.ConversationalMemoryHandler;
import org.opensearch.ml.conversational.index.OpenSearchConversationalMemoryHandler;
import org.opensearch.tasks.Task;
import org.opensearch.transport.TransportService;

import lombok.extern.log4j.Log4j2;

/**
 * The CreateConversationAction that actually does all of the work
 */
@Log4j2
public class CreateConversationTransportAction extends HandledTransportAction<CreateConversationRequest, CreateConversationResponse> {

    private ConversationalMemoryHandler cmHandler;
    private Client client;

    /**
     * Constructor
     * @param transportService for inter-node communications
     * @param actionFilters not sure what this is for tbh
     * @param cmHandler Handler for conversational memory operations
     * @param client OS Client for dealing with OS
     */
    @Inject
    public CreateConversationTransportAction(
        TransportService transportService,
        ActionFilters actionFilters,
        OpenSearchConversationalMemoryHandler cmHandler, 
        Client client
    ) {
        super(CreateConversationAction.NAME, transportService, actionFilters, CreateConversationRequest::new);
        this.cmHandler = cmHandler;
        this.client = client;
    }

    @Override
    protected void doExecute(Task task, CreateConversationRequest request, ActionListener<CreateConversationResponse> actionListener) {
        String name = request.getName();
        try (ThreadContext.StoredContext context = client.threadPool().getThreadContext().newStoredContext(true)) {
            ActionListener<CreateConversationResponse> internalListener = ActionListener.runBefore(actionListener, () -> context.restore());
            ActionListener<String> al = ActionListener.wrap(r -> {
                internalListener.onResponse(new CreateConversationResponse(r));
            }, e -> {
                log.error(e.toString());
                internalListener.onFailure(e);
            });

            if(name == null) {
                cmHandler.createConversation(al);
            } else {
                cmHandler.createConversation(name, al);
            }
        } catch(Exception e) {
            log.error("Failed to create new conversation with name " + request.getName(), e);
            actionListener.onFailure(e);
        }
    }


}