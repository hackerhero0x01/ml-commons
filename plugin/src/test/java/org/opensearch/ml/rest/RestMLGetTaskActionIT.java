/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.rest;

import java.io.IOException;

import org.junit.Rule;
import org.junit.rules.ExpectedException;
import org.opensearch.client.ResponseException;
import org.opensearch.ml.common.parameter.FunctionName;
import org.opensearch.ml.common.parameter.MLTaskState;
import org.opensearch.ml.utils.TestHelper;

public class RestMLGetTaskActionIT extends MLCommonsRestTestCase {
    @Rule
    public ExpectedException exceptionRule = ExpectedException.none();

    public void testGetModelAPI_EmptyResources() throws IOException {
        exceptionRule.expect(ResponseException.class);
        exceptionRule.expectMessage("Fail to find task");
        TestHelper.makeRequest(client(), "GET", "/_plugins/_ml/tasks/111222333", null, "", null);
    }

    public void testGetTaskAPI_Success() throws IOException {
        trainAsyncWithSample(trainResult -> {
            assertFalse(trainResult.containsKey("model_id"));
            String taskId = (String) trainResult.get("task_id");
            assertNotNull(taskId);
            String status = (String) trainResult.get("status");
            assertEquals(MLTaskState.CREATED.name(), status);
            try {
                getTask(client(), taskId, task -> {
                    String algorithm = (String) task.get("function_name");
                    assertEquals(FunctionName.SAMPLE_ALGO.name(), algorithm);
                });
            } catch (IOException e) {
                assertNull(e);
            }
        }, true);
    }
}
