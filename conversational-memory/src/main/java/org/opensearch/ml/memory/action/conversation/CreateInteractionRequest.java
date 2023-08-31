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
import java.util.Map;

import org.opensearch.action.ActionRequest;
import org.opensearch.action.ActionRequestValidationException;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.ml.common.conversation.ActionConstants;
import org.opensearch.rest.RestRequest;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Action Request for create interaction
 */
@AllArgsConstructor
public class CreateInteractionRequest extends ActionRequest {
    @Getter
    private String conversationId;
    @Getter
    private String input;
    @Getter
    private String promptTemplate;
    @Getter
    private String response;
    @Getter
    private String origin;
    @Getter
    private String additionalInfo;

    /**
     * Constructor
     * @param in stream to read this request from
     * @throws IOException if something breaks or there's no p.i.request in the stream
     */
    public CreateInteractionRequest(StreamInput in) throws IOException {
        super(in);
        this.conversationId = in.readString();
        this.input = in.readString();
        this.promptTemplate = in.readString();
        this.response = in.readString();
        this.origin = in.readOptionalString();
        this.additionalInfo = in.readOptionalString();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeString(conversationId);
        out.writeString(input);
        out.writeString(promptTemplate);
        out.writeString(response);
        out.writeOptionalString(origin);
        out.writeOptionalString(additionalInfo);
    }

    @Override
    public ActionRequestValidationException validate() {
        ActionRequestValidationException exception = null;
        if (this.conversationId == null) {
            exception = addValidationError("Interaction MUST belong to a conversation ID", exception);
        }
        return exception;
    }

    /**
     * Create a PutInteractionRequest from a RestRequest
     * @param request a RestRequest for a put interaction op
     * @return new PutInteractionRequest object
     * @throws IOException if something goes wrong reading from request
     */
    public static CreateInteractionRequest fromRestRequest(RestRequest request) throws IOException {
        Map<String, String> body = request.contentParser().mapStrings();
        String cid = request.param(ActionConstants.CONVERSATION_ID_FIELD);
        String inp = body.get(ActionConstants.INPUT_FIELD);
        String prmpt = body.get(ActionConstants.PROMPT_TEMPLATE_FIELD);
        String rsp = body.get(ActionConstants.AI_RESPONSE_FIELD);
        String ogn = body.get(ActionConstants.RESPONSE_ORIGIN_FIELD);
        String addinf = body.get(ActionConstants.ADDITIONAL_INFO_FIELD);
        return new CreateInteractionRequest(cid, inp, prmpt, rsp, ogn, addinf);
    }

}
