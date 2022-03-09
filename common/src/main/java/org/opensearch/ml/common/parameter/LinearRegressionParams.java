/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.common.parameter;

import lombok.Builder;
import lombok.Data;
import org.opensearch.common.ParseField;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.io.stream.StreamOutput;
import org.opensearch.common.xcontent.NamedXContentRegistry;
import org.opensearch.common.xcontent.XContentBuilder;
import org.opensearch.common.xcontent.XContentParser;
import org.opensearch.ml.common.annotation.MLAlgoParameter;

import java.io.IOException;
import java.util.Locale;

import static org.opensearch.common.xcontent.XContentParserUtils.ensureExpectedToken;

@Data
@MLAlgoParameter(algorithms={FunctionName.LINEAR_REGRESSION})
public class LinearRegressionParams implements MLAlgoParams {

    public static final String PARSE_FIELD_NAME = FunctionName.LINEAR_REGRESSION.name();
    public static final NamedXContentRegistry.Entry XCONTENT_REGISTRY = new NamedXContentRegistry.Entry(
            MLAlgoParams.class,
            new ParseField(PARSE_FIELD_NAME),
            it -> parse(it)
    );

    public static final String OBJECTIVE_FIELD = "objective";
    public static final String OPTIMISER_FIELD = "optimiser";
    public static final String LEARNING_RATE_FIELD = "learning_rate";
    public static final String MOMENTUM_TYPE_FIELD = "momentum_type";
    public static final String MOMENTUM_FACTOR_FIELD = "momentum_factor";
    public static final String EPSILON_FIELD = "epsilon";
    public static final String BETA1_FIELD = "beta1";
    public static final String BETA2_FIELD = "beta2";
    public static final String DECAY_RATE_FIELD = "decay_rate";
    public static final String EPOCHS_FIELD = "epochs";
    public static final String BATCH_SIZE_FIELD = "batch_size";
    public static final String SEED_FIELD = "seed";
    public static final String TARGET_FIELD = "target";

    private ObjectiveType objectiveType;
    private OptimizerType optimizerType;
    private Double learningRate;
    private MomentumType momentumType;
    private Double momentumFactor;
    private Double epsilon;
    private Double beta1;
    private Double beta2;
    private Double decayRate;
    private Integer epochs;
    private Integer batchSize;
    private Long seed;
    private String target;

    @Builder(toBuilder = true)
    public LinearRegressionParams(ObjectiveType objectiveType, OptimizerType optimizerType, Double learningRate, MomentumType momentumType, Double momentumFactor, Double epsilon, Double beta1, Double beta2, Double decayRate, Integer epochs, Integer batchSize, Long seed, String target) {
        this.objectiveType = objectiveType;
        this.optimizerType = optimizerType;
        this.learningRate = learningRate;
        this.momentumType = momentumType;
        this.momentumFactor = momentumFactor;
        this.epsilon = epsilon;
        this.beta1 = beta1;
        this.beta2 = beta2;
        this.decayRate = decayRate;
        this.epochs = epochs;
        this.batchSize = batchSize;
        this.seed = seed;
        this.target = target;
    }

    public LinearRegressionParams(StreamInput in) throws IOException {
        if (in.readBoolean()) {
            this.objectiveType = in.readEnum(ObjectiveType.class);
        }
        if (in.readBoolean()) {
            this.optimizerType = in.readEnum(OptimizerType.class);
        }
        this.learningRate = in.readOptionalDouble();
        if (in.readBoolean()) {
            this.momentumType = in.readEnum(MomentumType.class);
        }
        this.momentumFactor = in.readOptionalDouble();
        this.epsilon = in.readOptionalDouble();
        this.beta1 = in.readOptionalDouble();
        this.beta2 = in.readOptionalDouble();
        this.decayRate = in.readOptionalDouble();
        this.epochs = in.readOptionalInt();
        this.batchSize = in.readOptionalInt();
        this.seed = in.readOptionalLong();
        this.target = in.readOptionalString();
    }

    public static MLAlgoParams parse(XContentParser parser) throws IOException {
        ObjectiveType objective = null;
        OptimizerType optimizerType = null;
        Double learningRate = null;
        MomentumType momentumType = null;
        Double momentumFactor = null;
        Double epsilon = null;
        Double beta1 = null;
        Double beta2 = null;
        Double decayRate = null;
        Integer epochs = null;
        Integer batchSize = null;
        Long seed = null;
        String target = null;

        ensureExpectedToken(XContentParser.Token.START_OBJECT, parser.currentToken(), parser);
        while (parser.nextToken() != XContentParser.Token.END_OBJECT) {
            String fieldName = parser.currentName();
            parser.nextToken();

            switch (fieldName) {
                case OBJECTIVE_FIELD:
                    objective = ObjectiveType.valueOf(parser.text().toUpperCase(Locale.ROOT));
                    break;
                case OPTIMISER_FIELD:
                    optimizerType = OptimizerType.valueOf(parser.text().toUpperCase(Locale.ROOT));
                    break;
                case LEARNING_RATE_FIELD:
                    learningRate = parser.doubleValue(false);
                    break;
                case MOMENTUM_TYPE_FIELD:
                    momentumType = MomentumType.valueOf(parser.text().toUpperCase(Locale.ROOT));
                    break;
                case MOMENTUM_FACTOR_FIELD:
                    momentumFactor = parser.doubleValue(false);
                    break;
                case EPSILON_FIELD:
                    epsilon = parser.doubleValue(false);
                    break;
                case BETA1_FIELD:
                    beta1 = parser.doubleValue(false);
                    break;
                case BETA2_FIELD:
                    beta2 = parser.doubleValue(false);
                    break;
                case DECAY_RATE_FIELD:
                    decayRate = parser.doubleValue(false);
                    break;
                case EPOCHS_FIELD:
                    epochs = parser.intValue(false);
                    break;
                case BATCH_SIZE_FIELD:
                    batchSize = parser.intValue(false);
                    break;
                case SEED_FIELD:
                    seed = parser.longValue(false);
                    break;
                case TARGET_FIELD:
                    target = parser.text();
                    break;
                default:
                    parser.skipChildren();
                    break;
            }
        }
        return new LinearRegressionParams(objective,  optimizerType,  learningRate,  momentumType,  momentumFactor, epsilon, beta1, beta2,decayRate, epochs, batchSize, seed, target);
    }

    @Override
    public String getWriteableName() {
        return PARSE_FIELD_NAME;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        if (objectiveType != null) {
            out.writeBoolean(true);
            out.writeEnum(objectiveType);
        } else {
            out.writeBoolean(false);
        }
        if (optimizerType != null) {
            out.writeBoolean(true);
            out.writeEnum(optimizerType);
        } else {
            out.writeBoolean(false);
        }
        out.writeOptionalDouble(learningRate);
        if (momentumType != null) {
            out.writeBoolean(true);
            out.writeEnum(momentumType);
        } else {
            out.writeBoolean(false);
        }
        out.writeOptionalDouble(momentumFactor);
        out.writeOptionalDouble(epsilon);
        out.writeOptionalDouble(beta1);
        out.writeOptionalDouble(beta2);
        out.writeOptionalDouble(decayRate);
        out.writeOptionalInt(epochs);
        out.writeOptionalInt(batchSize);
        out.writeOptionalLong(seed);
        out.writeOptionalString(target);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        if (objectiveType != null) {
            builder.field(OBJECTIVE_FIELD, objectiveType);
        }
        if (optimizerType != null) {
            builder.field(OPTIMISER_FIELD, optimizerType);
        }
        if (learningRate != null) {
            builder.field(LEARNING_RATE_FIELD, learningRate);
        }
        if (momentumType != null) {
            builder.field(MOMENTUM_TYPE_FIELD, momentumType);
        }
        if (momentumFactor != null) {
            builder.field(MOMENTUM_FACTOR_FIELD, momentumFactor);
        }
        if (epsilon != null) {
            builder.field(EPSILON_FIELD, epsilon);
        }
        if (beta1 != null) {
            builder.field(BETA1_FIELD, beta1);
        }
        if (beta2 != null) {
            builder.field(BETA2_FIELD, beta2);
        }
        if (decayRate != null) {
            builder.field(DECAY_RATE_FIELD, decayRate);
        }
        if (epochs != null) {
            builder.field(EPOCHS_FIELD, epochs);
        }
        if (batchSize != null) {
            builder.field(BATCH_SIZE_FIELD, batchSize);
        }
        if (seed != null) {
            builder.field(SEED_FIELD, seed);
        }
        if (target != null) {
            builder.field(TARGET_FIELD, target);
        }
        builder.endObject();
        return builder;
    }

    @Override
    public int getVersion() {
        return 1;
    }

    public enum ObjectiveType {
        SQUARED_LOSS,
        ABSOLUTE_LOSS,
        HUBER;
        public static ObjectiveType from(String value) {
            try{
                return ObjectiveType.valueOf(value);
            } catch (Exception e) {
                throw new IllegalArgumentException("Wrong objective type");
            }
        }
    }

    public enum MomentumType {
        STANDARD,
        NESTEROV;

        public static MomentumType from(String value) {
            try{
                return MomentumType.valueOf(value);
            } catch (Exception e) {
                throw new IllegalArgumentException("Wrong momentum type");
            }
        }
    }

    public enum OptimizerType {
        SIMPLE_SGD,
        LINEAR_DECAY_SGD,
        SQRT_DECAY_SGD,
        ADA_GRAD,
        ADA_DELTA,
        ADAM,
        RMS_PROP;

        public static OptimizerType from(String value) {
            try{
                return OptimizerType.valueOf(value);
            } catch (Exception e) {
                throw new IllegalArgumentException("Wrong optimizer type");
            }
        }
    }
}
