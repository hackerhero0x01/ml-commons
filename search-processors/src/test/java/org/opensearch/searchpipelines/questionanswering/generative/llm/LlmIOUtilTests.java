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
package org.opensearch.searchpipelines.questionanswering.generative.llm;

import java.util.Collections;

import org.opensearch.test.OpenSearchTestCase;

public class LlmIOUtilTests extends OpenSearchTestCase {

    public void testCtor() {
        assertNotNull(new LlmIOUtil());
    }

    public void testChatCompletionInput() {
        final ChatCompletionInput input = LlmIOUtil
            .createChatCompletionInput("model", "question", Collections.emptyList(), Collections.emptyList(), 0);
        assertEquals(Llm.ModelProvider.OPENAI, input.getModelProvider());
    }

    public void testChatCompletionInput_whenMissingLlmModel() {
        final ChatCompletionInput input = LlmIOUtil
            .createChatCompletionInput(null, "question", Collections.emptyList(), Collections.emptyList(), 0);
        assertEquals(Llm.ModelProvider.OPENAI, input.getModelProvider());
    }

    public void testChatCompletionInputForBedrock() {
        final ChatCompletionInput input = LlmIOUtil
            .createChatCompletionInput("bedrock/model", "question", Collections.emptyList(), Collections.emptyList(), 0);
        assertEquals(Llm.ModelProvider.BEDROCK, input.getModelProvider());
    }

    public void testChatCompletionInputForOciGenai() {
        final ChatCompletionInput input = LlmIOUtil
            .createChatCompletionInput("oci_genai/model", "question", Collections.emptyList(), Collections.emptyList(), 0);
        assertEquals(Llm.ModelProvider.OCI_GENAI, input.getModelProvider());
    }
}
