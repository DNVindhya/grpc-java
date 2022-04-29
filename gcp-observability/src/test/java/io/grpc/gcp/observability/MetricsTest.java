/*
 * Copyright 2022 The gRPC Authors
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

package io.grpc.gcp.observability;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import io.grpc.ManagedChannelBuilder;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.gcp.observability.interceptors.ConfigFilterHelper;
import io.grpc.gcp.observability.interceptors.ConfigFilterHelper.FilterParams;
import io.grpc.gcp.observability.interceptors.InternalLoggingChannelInterceptor;
import io.grpc.gcp.observability.interceptors.InternalLoggingServerInterceptor.FactoryImpl;
import io.grpc.gcp.observability.interceptors.LogHelper;
import io.grpc.gcp.observability.logging.GcpLogSink;
import io.grpc.gcp.observability.logging.Sink;
import io.grpc.internal.TimeProvider;
import io.grpc.observabilitylog.v1.GrpcLogRecord;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.testing.protobuf.SimpleServiceGrpc;
import io.opencensus.common.Duration;
import io.opencensus.contrib.grpc.metrics.RpcViews;
import io.opencensus.exporter.stats.stackdriver.StackdriverStatsConfiguration;
import io.opencensus.exporter.stats.stackdriver.StackdriverStatsExporter;
import io.opencensus.exporter.trace.stackdriver.StackdriverTraceConfiguration;
import io.opencensus.exporter.trace.stackdriver.StackdriverTraceExporter;
import io.opencensus.trace.Tracing;
import io.opencensus.trace.config.TraceConfig;
import io.opencensus.trace.samplers.Samplers;
import java.io.IOException;
import java.util.Map;
// import org.junit.Ignore;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

@RunWith(JUnit4.class)
public class MetricsTest {

  @Rule
  public final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();

  private static final String PROJECT_ID = "grpc-testing";
  private static final Map<String, String> locationTags = ImmutableMap.of(
      "project_id", "PROJECT",
      "location", "us-central1-c",
      "cluster_name", "grpc-observability-cluster",
      "namespace_name", "default" ,
      "pod_name", "app1-6c7c58f897-n92c5");
  private static final Map<String, String> customTags = ImmutableMap.of(
      "KEY1", "Value1",
      "KEY2", "VALUE2");
  private static final long flushLimit = 100L;

  /**
   * Cloud logging test using LoggingChannelProvider and LoggingServerProvider.
   *
   * <p> Ignoring test, because it calls external CLoud Logging APIs.
   * To test cloud logging setup,
   * 1. Set up Cloud Logging Auth credentials
   * 2. Assign permissions to service account to write logs to project specified by
   * variable PROJECT_ID
   * 3. Comment @Ignore annotation
   * </p>
   */
  // @Ignore
  @Test
  public void metrics_withLogging()
      throws IOException, InterruptedException {
    Sink sink = new GcpLogSink(PROJECT_ID, locationTags, customTags, flushLimit);
    LogHelper spyLogHelper = spy(new LogHelper(sink, TimeProvider.SYSTEM_TIME_PROVIDER));
    ConfigFilterHelper mockFilterHelper = mock(ConfigFilterHelper.class);
    FilterParams logAlwaysFilterParams =
        FilterParams.create(true, 0, 0);
    when(mockFilterHelper.isMethodToBeLogged(any(MethodDescriptor.class)))
        .thenReturn(logAlwaysFilterParams);
    when(mockFilterHelper.isEventToBeLogged(any(GrpcLogRecord.EventType.class)))
        .thenReturn(true);

    RpcViews.registerAllGrpcViews();
    TraceConfig traceConfig = Tracing.getTraceConfig();
    traceConfig.updateActiveTraceParams(
        traceConfig.getActiveTraceParams().toBuilder()
            .setSampler(Samplers.alwaysSample())
            .build());

    StackdriverStatsExporter.createAndRegister(
        StackdriverStatsConfiguration.builder()
            .setProjectId(PROJECT_ID)
            .setExportInterval(Duration.create(5, 0))
            .build());
    StackdriverTraceExporter.createAndRegister(
        StackdriverTraceConfiguration.builder().setProjectId(PROJECT_ID).build());

    LoggingServerProvider.init(
        new FactoryImpl(spyLogHelper, mockFilterHelper));
    Server server = ServerBuilder.forPort(0).addService(new LoggingTestHelper.SimpleServiceImpl())
        .build().start();
    int port = cleanupRule.register(server).getPort();
    LoggingChannelProvider.init(
        new InternalLoggingChannelInterceptor.FactoryImpl(spyLogHelper, mockFilterHelper));
    SimpleServiceGrpc.SimpleServiceBlockingStub stub = SimpleServiceGrpc.newBlockingStub(
        cleanupRule.register(ManagedChannelBuilder.forAddress("localhost", port)
            .usePlaintext().build()));
    assertThat(LoggingTestHelper.makeUnaryRpcViaClientStub("buddy", stub))
        .isEqualTo("Hello buddy");
    assertThat(Mockito.mockingDetails(spyLogHelper).getInvocations().size()).isGreaterThan(11);
    sink.close();
    LoggingChannelProvider.shutdown();
    LoggingServerProvider.shutdown();
    System.out.println("Waiting for exception, if there are any");
    TimeUnit.SECONDS.sleep(60);
  }
}

