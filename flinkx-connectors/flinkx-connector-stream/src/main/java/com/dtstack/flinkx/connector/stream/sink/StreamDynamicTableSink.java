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

package com.dtstack.flinkx.connector.stream.sink;

import com.dtstack.flinkx.streaming.api.functions.sink.DtOutputFormatSinkFunction;

import org.apache.flink.table.api.TableSchema;
import org.apache.flink.table.connector.ChangelogMode;
import org.apache.flink.table.connector.sink.DynamicTableSink;
import org.apache.flink.table.connector.sink.SinkFunctionProvider;
import org.apache.flink.table.types.DataType;

import com.dtstack.flinkx.conf.FieldConf;
import com.dtstack.flinkx.connector.stream.conf.StreamSinkConf;
import com.dtstack.flinkx.connector.stream.outputFormat.StreamOutputFormatBuilder;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author chuixue
 * @create 2021-04-09 09:20
 * @description SinkFunction的包装类DynamicTableSink
 **/

public class StreamDynamicTableSink implements DynamicTableSink {

    private StreamSinkConf sinkConf;
    private DataType type;
    private final TableSchema tableSchema;

    public StreamDynamicTableSink(StreamSinkConf sinkConf, DataType type, TableSchema tableSchema) {
        this.sinkConf = sinkConf;
        this.type = type;
        this.tableSchema = tableSchema;
    }

    @Override
    public ChangelogMode getChangelogMode(ChangelogMode requestedMode) {
        return requestedMode;
    }

    @Override
    public SinkFunctionProvider getSinkRuntimeProvider(Context context) {

        // 一些其他参数的封装,如果有
        sinkConf.setPrint(true);
        List<FieldConf> fieldList = Arrays
                .stream(tableSchema.getFieldNames())
                .map(e -> {
                    FieldConf fieldConf = new FieldConf();
                    fieldConf.setName(e);
                    return fieldConf;
                })
                .collect(
                        Collectors.toList());
        sinkConf.setColumn(fieldList);

        StreamOutputFormatBuilder builder = StreamOutputFormatBuilder
                .builder()
                .setStreamSinkConf(sinkConf)
                .setConverter(context.createDataStructureConverter(type));

        return SinkFunctionProvider.of(new DtOutputFormatSinkFunction(builder.finish()), 1);
    }

    @Override
    public DynamicTableSink copy() {
        return new StreamDynamicTableSink(sinkConf, type, tableSchema);
    }

    @Override
    public String asSummaryString() {
        return "StreamDynamicTableSink";
    }
}