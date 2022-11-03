package org.opensearch.ml.common.transport.load;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.opensearch.action.ActionRequest;
import org.opensearch.action.ActionRequestValidationException;
import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.common.io.stream.StreamOutput;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.transport.TransportAddress;
import org.opensearch.common.xcontent.*;
import org.opensearch.ml.common.TestHelper;
import org.opensearch.search.SearchModule;
import org.opensearch.tasks.Task;
import org.opensearch.tasks.TaskId;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.util.Collections;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.Assert.*;

public class MLLoadModelRequestTest {

    private MLLoadModelRequest mlLoadModelRequest;

    @Before
    public void setUp() throws Exception {
        mlLoadModelRequest = MLLoadModelRequest.builder().
                modelId("modelId").
                modelNodeIds(new String[]{"modelNodeIds"}).
                async(true).
                dispatchTask(true).
                build();

    }

    @Test
    public void testValidateWithBuilder() {
         MLLoadModelRequest request = MLLoadModelRequest.builder().
                 modelId("modelId").
                 build();
        assertNull(request.validate());
    }

    @Test
    public void testValidateWithoutBuilder() {
        MLLoadModelRequest request = new MLLoadModelRequest("modelId", true);
        assertNull(request.validate());
    }

    @Test
    public void validate_Exception_WithNullModelId() {
        MLLoadModelRequest request = MLLoadModelRequest.builder().
                modelId(null).
                modelNodeIds(new String[]{"modelNodeIds"}).
                async(true).
                dispatchTask(true).
                build();
        ActionRequestValidationException exception = request.validate();
        assertEquals("Validation Failed: 1: ML model id can't be null;", exception.getMessage());
    }

    @Test
    public void writeTo_Success() throws IOException {

        MLLoadModelRequest request = mlLoadModelRequest;
        BytesStreamOutput bytesStreamOutput = new BytesStreamOutput();
        request.writeTo(bytesStreamOutput);
        request = new MLLoadModelRequest(bytesStreamOutput.bytes().streamInput());

        assertEquals("modelId", request.getModelId());
        assertArrayEquals(new String[]{"modelNodeIds"}, request.getModelNodeIds());
        assertTrue(request.isAsync());
        assertTrue(request.isDispatchTask());
    }

    @Test(expected = UncheckedIOException.class)
    public void fromActionRequest_IOException() {
        ActionRequest actionRequest = new ActionRequest() {
            @Override
            public ActionRequestValidationException validate() {
                return null;
            }

            @Override
            public void writeTo(StreamOutput out) throws IOException {
                throw new IOException("test");
            }
        };
        MLLoadModelRequest.fromActionRequest(actionRequest);
    }

    @Test
    public void fromActionRequest_Success_WithMLLoadModelRequest() {
        MLLoadModelRequest request = MLLoadModelRequest.builder().
                modelId("modelId").
                build();
        assertSame(MLLoadModelRequest.fromActionRequest(request), request);
    }

    @Test
    public void fromActionRequest_Success_WithNonMLLoadModelRequest() {
        MLLoadModelRequest request = mlLoadModelRequest;
        ActionRequest actionRequest = new ActionRequest() {
            @Override
            public ActionRequestValidationException validate() {
                return null;
            }

            @Override
            public void writeTo(StreamOutput out) throws IOException {
                request.writeTo(out);
            }
        };
        MLLoadModelRequest result = MLLoadModelRequest.fromActionRequest(actionRequest);
        assertNotSame(result, request);
        assertEquals(request.isAsync(), result.isAsync());
        assertEquals(request.isDispatchTask(), result.isDispatchTask());
    }

    @Test
    public void testParse() throws Exception {
        String modelId = "modelId";
        String expectedInputStr = "{\"node_ids\":[\"modelNodeIds\"]}";
        parseFromJsonString(modelId, expectedInputStr, parsedInput -> {
            assertEquals("modelId", parsedInput.getModelId());
            assertArrayEquals(new String [] {"modelNodeIds"}, parsedInput.getModelNodeIds());
            assertFalse(parsedInput.isAsync());
            assertTrue(parsedInput.isDispatchTask());}
        );
    }

    @Test
    public void testParseWithInvalidField() throws Exception {
        String modelId = "modelId";
        String withInvalidFieldInputStr = "{\"void\":\"void\", \"dispatchTask\":\"false\", \"async\":\"true\", \"node_ids\":[\"modelNodeIds\"]}";
        parseFromJsonString(modelId, withInvalidFieldInputStr, parsedInput -> {
            assertEquals("modelId", parsedInput.getModelId());
            assertArrayEquals(new String [] {"modelNodeIds"}, parsedInput.getModelNodeIds());
            assertFalse(parsedInput.isAsync());
            assertTrue(parsedInput.isDispatchTask());}
        );
    }

    private void parseFromJsonString(String modelId, String expectedInputStr, Consumer<MLLoadModelRequest> verify) throws Exception {
        XContentParser parser = XContentType.JSON.xContent().createParser(new NamedXContentRegistry(new SearchModule(Settings.EMPTY,
                Collections.emptyList()).getNamedXContents()), LoggingDeprecationHandler.INSTANCE, expectedInputStr);
        parser.nextToken();
        MLLoadModelRequest parsedInput = MLLoadModelRequest.parse(parser, modelId);
        verify.accept(parsedInput);
    }
}
