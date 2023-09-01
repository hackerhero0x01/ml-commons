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
package org.opensearch.ml.memory.action.conversation;

import static org.opensearch.action.ValidateActions.addValidationError;

import java.io.IOException;

import org.opensearch.action.ActionRequest;
import org.opensearch.action.ActionRequestValidationException;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.ml.common.conversation.ActionConstants;
import org.opensearch.rest.RestRequest;

import lombok.Getter;

/**
 * ActionRequest for list conversations action
 */
public class GetConversationsRequest extends ActionRequest {
    @Getter
    private int maxResults = ActionConstants.DEFAULT_MAX_RESULTS;
    @Getter
    private int from = 0;

    /**
     * Constructor; returns from position 0
     * @param maxResults number of results to return
     */
    public GetConversationsRequest(int maxResults) {
        super();
        this.maxResults = maxResults;
    }

    /**
     * Constructor
     * @param maxResults number of results to return
     * @param from where to start from
     */
    public GetConversationsRequest(int maxResults, int from) {
        super();
        this.maxResults = maxResults;
        this.from = from;
    }

    /**
     * Constructor; defaults to 10 results returned from position 0
     */
    public GetConversationsRequest() {
        super();
    }

    /**
     * Constructor
     * @param in Input stream to read from. assumes there was a writeTo
     * @throws IOException if I can't read
     */
    public GetConversationsRequest(StreamInput in) throws IOException {
        super(in);
        this.maxResults = in.readInt();
        this.from = in.readInt();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeInt(maxResults);
        out.writeInt(from);
    }

    @Override
    public ActionRequestValidationException validate() {
        ActionRequestValidationException exception = null;
        if (this.maxResults <= 0) {
            exception = addValidationError("Can't list 0 or negative conversations", exception);
        }
        return exception;
    }

    /**
     * Creates a ListConversationsRequest from a RestRequest
     * @param request a RestRequest for a ListConversations
     * @return a new ListConversationsRequest
     * @throws IOException if something breaks
     */
    public static GetConversationsRequest fromRestRequest(RestRequest request) throws IOException {
        if (request.hasParam(ActionConstants.NEXT_TOKEN_FIELD)) {
            int maxResults = request.hasParam(ActionConstants.REQUEST_MAX_RESULTS_FIELD)
                ? Integer.parseInt(request.param(ActionConstants.REQUEST_MAX_RESULTS_FIELD))
                : ActionConstants.DEFAULT_MAX_RESULTS;

            int nextToken = Integer.parseInt(request.param(ActionConstants.NEXT_TOKEN_FIELD));

            return new GetConversationsRequest(maxResults, nextToken);
        } else {
            int maxResults = request.hasParam(ActionConstants.REQUEST_MAX_RESULTS_FIELD)
                ? Integer.parseInt(request.param(ActionConstants.REQUEST_MAX_RESULTS_FIELD))
                : ActionConstants.DEFAULT_MAX_RESULTS;

            return new GetConversationsRequest(maxResults);
        }
    }
}
