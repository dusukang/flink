/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.planner.plan.nodes.exec.common;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.io.InputFormat;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.connector.source.Boundedness;
import org.apache.flink.api.connector.source.Source;
import org.apache.flink.api.dag.Transformation;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.source.ParallelSourceFunction;
import org.apache.flink.streaming.api.functions.source.SourceFunction;
import org.apache.flink.streaming.api.operators.StreamSource;
import org.apache.flink.streaming.api.transformations.LegacySourceTransformation;
import org.apache.flink.table.api.TableException;
import org.apache.flink.table.api.config.ExecutionConfigOptions;
import org.apache.flink.table.connector.ParallelismProvider;
import org.apache.flink.table.connector.source.DataStreamScanProvider;
import org.apache.flink.table.connector.source.InputFormatProvider;
import org.apache.flink.table.connector.source.ScanTableSource;
import org.apache.flink.table.connector.source.SourceFunctionProvider;
import org.apache.flink.table.connector.source.SourceProvider;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.planner.connectors.TransformationScanProvider;
import org.apache.flink.table.planner.delegation.PlannerBase;
import org.apache.flink.table.planner.plan.nodes.exec.ExecNode;
import org.apache.flink.table.planner.plan.nodes.exec.ExecNodeBase;
import org.apache.flink.table.planner.plan.nodes.exec.MultipleTransformationTranslator;
import org.apache.flink.table.planner.plan.nodes.exec.spec.DynamicTableSourceSpec;
import org.apache.flink.table.runtime.connector.source.ScanRuntimeProviderContext;
import org.apache.flink.table.runtime.typeutils.InternalTypeInfo;
import org.apache.flink.table.types.logical.LogicalType;
import org.apache.flink.table.types.logical.RowType;

import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.Optional;

/**
 * Base {@link ExecNode} to read data from an external source defined by a {@link ScanTableSource}.
 */
public abstract class CommonExecTableSourceScan extends ExecNodeBase<RowData>
        implements MultipleTransformationTranslator<RowData> {
    public static final String FIELD_NAME_SCAN_TABLE_SOURCE = "scanTableSource";

    @JsonProperty(FIELD_NAME_SCAN_TABLE_SOURCE)
    private final DynamicTableSourceSpec tableSourceSpec;

    protected CommonExecTableSourceScan(
            DynamicTableSourceSpec tableSourceSpec,
            int id,
            LogicalType outputType,
            String description) {
        super(id, Collections.emptyList(), outputType, description);
        this.tableSourceSpec = tableSourceSpec;
    }

    public DynamicTableSourceSpec getTableSourceSpec() {
        return tableSourceSpec;
    }

    @Override
    protected Transformation<RowData> translateToPlanInternal(PlannerBase planner) {
        final StreamExecutionEnvironment env = planner.getExecEnv();
        final String operatorName = getDescription();
        final InternalTypeInfo<RowData> outputTypeInfo =
                InternalTypeInfo.of((RowType) getOutputType());
        final ScanTableSource tableSource = tableSourceSpec.getScanTableSource(planner);
        ScanTableSource.ScanRuntimeProvider provider =
                tableSource.getScanRuntimeProvider(ScanRuntimeProviderContext.INSTANCE);
        if (provider instanceof SourceFunctionProvider) {
            final SourceFunctionProvider sourceFunctionProvider = (SourceFunctionProvider) provider;
            final SourceFunction<RowData> function = sourceFunctionProvider.createSourceFunction();
            return createSourceFunctionTransformation(
                    env,
                    function,
                    sourceFunctionProvider.isBounded(),
                    operatorName,
                    outputTypeInfo);
        } else if (provider instanceof InputFormatProvider) {
            final InputFormat<RowData, ?> inputFormat =
                    ((InputFormatProvider) provider).createInputFormat();
            return createInputFormatTransformation(env, inputFormat, outputTypeInfo, operatorName);
        } else if (provider instanceof SourceProvider) {
            Source<RowData, ?, ?> source = ((SourceProvider) provider).createSource();
            // TODO: Push down watermark strategy to source scan
            DataStreamSource<RowData> rowDataStreamSource = env.fromSource(
                    source, WatermarkStrategy.noWatermarks(), operatorName, outputTypeInfo);
            final Configuration config = planner.getTableConfig().getConfiguration();
            if (config.get(ExecutionConfigOptions.TABLE_EXEC_SOURCE_FORCE_BREAK_CHAIN)) {
                rowDataStreamSource.disableChaining();
            }
            int confParallelism = rowDataStreamSource.getParallelism();
            final int scanParallelism = deriveSourceParallelism(
                    (ParallelismProvider) provider, confParallelism);
            Transformation<RowData> transformation = rowDataStreamSource.getTransformation();
            transformation.setParallelism(scanParallelism);
            return transformation;
        } else if (provider instanceof DataStreamScanProvider) {
            Transformation<RowData> transformation =
                    ((DataStreamScanProvider) provider).produceDataStream(env).getTransformation();
            transformation.setOutputType(outputTypeInfo);
            return transformation;
        } else if (provider instanceof TransformationScanProvider) {
            final Transformation<RowData> transformation =
                    ((TransformationScanProvider) provider).createTransformation();
            transformation.setOutputType(outputTypeInfo);
            return transformation;
        } else {
            throw new UnsupportedOperationException(
                    provider.getClass().getSimpleName() + " is unsupported now.");
        }
    }

    private int deriveSourceParallelism(
            ParallelismProvider parallelismProvider, int confParallelism) {
        final Optional<Integer> parallelismOptional = parallelismProvider.getParallelism();
        if (parallelismOptional.isPresent()) {
            int sourceParallelism = parallelismOptional.get();
            if (sourceParallelism <= 0) {
                throw new TableException(
                        String.format(
                                "Table: %s configured source parallelism: "
                                        + "%s should not be less than zero or equal to zero",
                                tableSourceSpec.getObjectIdentifier().asSummaryString(),
                                sourceParallelism));
            }
            return sourceParallelism;
        } else {
            return confParallelism;
        }
    }

    /**
     * Adopted from {@link StreamExecutionEnvironment#addSource(SourceFunction, String,
     * TypeInformation)} but with custom {@link Boundedness}.
     */
    protected Transformation<RowData> createSourceFunctionTransformation(
            StreamExecutionEnvironment env,
            SourceFunction<RowData> function,
            boolean isBounded,
            String operatorName,
            TypeInformation<RowData> outputTypeInfo) {

        env.clean(function);

        final int parallelism;
        if (function instanceof ParallelSourceFunction) {
            parallelism = env.getParallelism();
        } else {
            parallelism = 1;
        }

        final Boundedness boundedness;
        if (isBounded) {
            boundedness = Boundedness.BOUNDED;
        } else {
            boundedness = Boundedness.CONTINUOUS_UNBOUNDED;
        }

        final StreamSource<RowData, ?> sourceOperator = new StreamSource<>(function, !isBounded);
        return new LegacySourceTransformation<>(
                operatorName, sourceOperator, outputTypeInfo, parallelism, boundedness);
    }

    /**
     * Creates a {@link Transformation} based on the given {@link InputFormat}. The implementation
     * is different for streaming mode and batch mode.
     */
    protected abstract Transformation<RowData> createInputFormatTransformation(
            StreamExecutionEnvironment env,
            InputFormat<RowData, ?> inputFormat,
            InternalTypeInfo<RowData> outputTypeInfo,
            String operatorName);
}
