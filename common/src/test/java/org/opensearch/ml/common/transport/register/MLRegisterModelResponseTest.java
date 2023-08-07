package org.opensearch.ml.common.transport.register;

import org.junit.Before;
import org.junit.Test;
import org.opensearch.ml.common.utils.StringUtils;
import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.core.xcontent.XContentBuilder;

import java.io.IOException;

import static org.junit.Assert.*;
import static org.junit.Assert.assertEquals;

public class MLRegisterModelResponseTest {

    private String taskId;
    private String status;

    @Before
    public void setUp() throws Exception {
        taskId = "test_id";
        status = "test";
    }

    @Test
    public void writeTo_Success() throws IOException {
        // Setup
        BytesStreamOutput bytesStreamOutput = new BytesStreamOutput();
        MLRegisterModelResponse response = new MLRegisterModelResponse(taskId, status);
        // Run the test
        response.writeTo(bytesStreamOutput);
        MLRegisterModelResponse parsedResponse = new MLRegisterModelResponse(bytesStreamOutput.bytes().streamInput());
        // Verify the results
        assertEquals(response.getTaskId(), parsedResponse.getTaskId());
        assertEquals(response.getStatus(), parsedResponse.getStatus());
    }

    @Test
    public void testToXContent() throws IOException {
        // Setup
        MLRegisterModelResponse response = new MLRegisterModelResponse(taskId, status);
        // Run the test
        XContentBuilder builder = XContentFactory.jsonBuilder();
        response.toXContent(builder, ToXContent.EMPTY_PARAMS);
        assertNotNull(builder);
        String jsonStr = StringUtils.xContentBuilderToString(builder);
        // Verify the results
        assertEquals("{\"task_id\":\"test_id\"," +
                "\"status\":\"test\"}", jsonStr);
    }
}
