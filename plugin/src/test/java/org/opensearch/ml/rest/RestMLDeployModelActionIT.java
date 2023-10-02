/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.ml.rest;

import static org.opensearch.ml.common.MLTask.MODEL_ID_FIELD;

import java.io.IOException;
import java.util.Map;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.ExpectedException;
import org.opensearch.ml.common.MLTaskState;
import org.opensearch.ml.common.transport.model_group.MLRegisterModelGroupInput;
import org.opensearch.ml.common.transport.register.MLRegisterModelInput;
import org.opensearch.ml.utils.TestHelper;

public class RestMLDeployModelActionIT extends MLCommonsRestTestCase {
    @Rule
    public ExpectedException exceptionRule = ExpectedException.none();
    private MLRegisterModelInput registerModelInput;
    private MLRegisterModelGroupInput mlRegisterModelGroupInput;
    private String modelGroupId;

    @Before
    public void setup() throws IOException {
        mlRegisterModelGroupInput = MLRegisterModelGroupInput.builder().name("testGroupID").description("This is test Group").build();
        registerModelGroup(client(), TestHelper.toJsonString(mlRegisterModelGroupInput), registerModelGroupResult -> {
            this.modelGroupId = (String) registerModelGroupResult.get("model_group_id");
        });
        registerModelInput = createRegisterModelInput(modelGroupId);
    }

    public void testReDeployModel() throws InterruptedException, IOException {
        // Skip test if running on Mac OS, https://github.com/opensearch-project/ml-commons/issues/844
        Assume.assumeFalse(System.getProperty("os.name").startsWith("Mac OS X"));

        // Register Model
        String taskId = registerModel(TestHelper.toJsonString(registerModelInput));
        waitForTask(taskId, MLTaskState.COMPLETED);
        getTask(client(), taskId, response -> {
            String model_id = (String) response.get(MODEL_ID_FIELD);
            try {
                // Deploy Model
                String taskId1 = deployModel(model_id);
                getTask(client(), taskId1, innerResponse -> { assertEquals(model_id, innerResponse.get(MODEL_ID_FIELD)); });
                waitForTask(taskId1, MLTaskState.COMPLETED);

                // Undeploy Model
                Map<String, Object> undeployresponse = undeployModel(model_id);
                for (Map.Entry<String, Object> entry : undeployresponse.entrySet()) {
                    Map stats = (Map) ((Map) entry.getValue()).get("stats");
                    assertEquals("undeployed", stats.get(model_id));
                }

                // Deploy Model again
                taskId1 = deployModel(model_id);
                getTask(client(), taskId1, innerResponse -> { logger.info("Re-Deploy model {}", innerResponse); });
                waitForTask(taskId1, MLTaskState.COMPLETED);

                getModel(client(), model_id, model -> {
                    logger.info("Get Model after re-deploy {}", model);
                    assertEquals("DEPLOYED", model.get("model_state"));
                });

            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}
