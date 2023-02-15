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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.google.cloud.trace.v1.TraceServiceClient;
import com.google.cloud.trace.v1.TraceServiceClient.ListTracesPagedResponse;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.cloudtrace.v1.GetTraceRequest;
import com.google.devtools.cloudtrace.v1.ListTracesRequest;
import com.google.devtools.cloudtrace.v1.Trace;
import com.google.devtools.cloudtrace.v1.TraceSpan;
import com.google.protobuf.util.Timestamps;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.StaticTestingClassLoader;
import io.grpc.gcp.observability.interceptors.ConfigFilterHelper;
import io.grpc.gcp.observability.interceptors.ConfigFilterHelper.FilterParams;
import io.grpc.gcp.observability.interceptors.InternalLoggingChannelInterceptor;
import io.grpc.gcp.observability.interceptors.InternalLoggingServerInterceptor;
import io.grpc.gcp.observability.interceptors.LogHelper;
import io.grpc.gcp.observability.logging.GcpLogSink;
import io.grpc.gcp.observability.logging.Sink;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.testing.protobuf.SimpleServiceGrpc;
import io.opencensus.trace.samplers.Samplers;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class TracesTest {

  @ClassRule
  public static final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();

  private static final String PROJECT_ID = "PROJECT";
  private static final String CUSTOM_TAG_KEY = "service";
  private static final String CUSTOM_TAG_VALUE =
      String.format("payment-%s", String.valueOf(System.currentTimeMillis()));
  private static final Map<String, String> CUSTOM_TAGS =
      Collections.singletonMap(CUSTOM_TAG_KEY, CUSTOM_TAG_VALUE);

  private static final ImmutableMap<String, String> LOCATION_TAGS = ImmutableMap.of(
      "project_id", "PROJECT",
      "location", "us-central1-c",
      "cluster_name", "grpc-observability-cluster",
      "namespace_name", "default" ,
      "pod_name", "app1-6c7c58f897-n92c5");

  private final StaticTestingClassLoader classLoader =
      new StaticTestingClassLoader(getClass().getClassLoader(),
          Pattern.compile("io\\.grpc\\..*|io\\.opencensus\\..*"));

  /**
   * End to end cloud trace test.
   *
   * <p>Ignoring test, because it calls external Cloud Tracing APIs. To test cloud trace setup
   * locally,
   * 1. Set up Cloud auth credentials
   * 2. Assign permissions to service account to write traces to project specified by variable
   * PROJECT_ID
   * 3. Comment @Ignore annotation
   * 4. This test is expected to pass when ran with above setup. This has been verified manually.
   */
  @Ignore
  @Test
  public void testTracesExporter() throws Exception {
    Class<?> runnable =
        classLoader.loadClass(TracesTest.StaticTestingClassTestTracesExporter.class.getName());
    ((Runnable) runnable.getDeclaredConstructor().newInstance()).run();
  }

  @Test
  public void testTracesLogsConnector() throws Exception {
    Class<?> runnable =
        classLoader.loadClass(TracesTest.StaticTestingClassTestTracesLogsExporter.class.getName());
    ((Runnable) runnable.getDeclaredConstructor().newInstance()).run();
  }

  public static final class StaticTestingClassTestTracesExporter implements Runnable {

    @Override
    public void run() {
      Sink mockSink = mock(GcpLogSink.class);
      ObservabilityConfig mockConfig = mock(ObservabilityConfig.class);
      InternalLoggingChannelInterceptor.Factory mockChannelInterceptorFactory =
          mock(InternalLoggingChannelInterceptor.Factory.class);
      InternalLoggingServerInterceptor.Factory mockServerInterceptorFactory =
          mock(InternalLoggingServerInterceptor.Factory.class);

      when(mockConfig.isEnableCloudTracing()).thenReturn(true);
      when(mockConfig.getSampler()).thenReturn(Samplers.alwaysSample());
      when(mockConfig.getProjectId()).thenReturn(PROJECT_ID);

      try {
        GcpObservability observability =
            GcpObservability.grpcInit(
                mockSink, mockConfig, mockChannelInterceptorFactory, mockServerInterceptorFactory);
        observability.registerStackDriverExporter(PROJECT_ID, CUSTOM_TAGS);

        Server server =
            ServerBuilder.forPort(0)
                .addService(new ObservabilityTestHelper.SimpleServiceImpl())
                .build()
                .start();
        int port = cleanupRule.register(server).getPort();
        SimpleServiceGrpc.SimpleServiceBlockingStub stub =
            SimpleServiceGrpc.newBlockingStub(
                cleanupRule.register(
                    ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build()));
        assertThat(ObservabilityTestHelper.makeUnaryRpcViaClientStub("buddy", stub))
            .isEqualTo("Hello buddy");
        // Adding sleep to ensure traces are exported before querying cloud tracing backend
        TimeUnit.SECONDS.sleep(10);

        TraceServiceClient traceServiceClient = TraceServiceClient.create();
        String traceFilter =
            String.format(
                "span:Sent.grpc.testing.SimpleService +%s:%s", CUSTOM_TAG_KEY, CUSTOM_TAG_VALUE);
        String traceOrder = "start";
        // Restrict time to last 1 minute
        long startMillis = System.currentTimeMillis() - ((60 * 1) * 1000);
        ListTracesRequest traceRequest =
            ListTracesRequest.newBuilder()
                .setProjectId(PROJECT_ID)
                .setStartTime(Timestamps.fromMillis(startMillis))
                .setEndTime(Timestamps.fromMillis(System.currentTimeMillis()))
                .setFilter(traceFilter)
                .setOrderBy(traceOrder)
                .build();
        ListTracesPagedResponse traceResponse = traceServiceClient.listTraces(traceRequest);
        assertThat(traceResponse.iterateAll()).isNotEmpty();
        List<String> traceIdList = new ArrayList<>();
        for (Trace t : traceResponse.iterateAll()) {
          traceIdList.add(t.getTraceId());
        }

        for (String traceId : traceIdList) {
          // This checks Cloud trace for the new trace that was just created.
          GetTraceRequest getTraceRequest =
              GetTraceRequest.newBuilder().setProjectId(PROJECT_ID).setTraceId(traceId).build();
          Trace trace = traceServiceClient.getTrace(getTraceRequest);
          assertThat(trace.getSpansList()).hasSize(3);
          for (TraceSpan span : trace.getSpansList()) {
            assertThat(span.getName()).contains("grpc.testing.SimpleService.UnaryRpc");
            assertThat(span.getLabelsMap().get(CUSTOM_TAG_KEY)).isEqualTo(CUSTOM_TAG_VALUE);
          }
        }
        observability.close();
      } catch (IOException | InterruptedException e) {
        throw new AssertionError("Exception while testing traces", e);
      }
    }
  }

  public static final class StaticTestingClassTestTracesLogsExporter implements Runnable {

    @Override
    public void run() {
      Sink sink =
          new GcpLogSink(
              PROJECT_ID, LOCATION_TAGS, CUSTOM_TAGS, Collections.emptySet());
      ObservabilityConfig mockConfig = mock(ObservabilityConfig.class);

      LogHelper spyLogHelper = spy(new LogHelper(sink));
      ConfigFilterHelper mockFilterHelper = mock(ConfigFilterHelper.class);
      InternalLoggingChannelInterceptor.Factory channelInterceptorFactory =
          new InternalLoggingChannelInterceptor.FactoryImpl(spyLogHelper, mockFilterHelper);
      InternalLoggingServerInterceptor.Factory serverInterceptorFactory =
          new InternalLoggingServerInterceptor.FactoryImpl(spyLogHelper, mockFilterHelper);

      when(mockConfig.isEnableCloudLogging()).thenReturn(true);
      FilterParams logAlwaysFilterParams = FilterParams.create(true, 1024, 10);
      when(mockFilterHelper.logRpcMethod(anyString(), eq(true)))
          .thenReturn(logAlwaysFilterParams);
      when(mockFilterHelper.logRpcMethod(anyString(), eq(false)))
          .thenReturn(logAlwaysFilterParams);

      when(mockConfig.isEnableCloudTracing()).thenReturn(true);
      when(mockConfig.getSampler()).thenReturn(Samplers.alwaysSample());
      when(mockConfig.getProjectId()).thenReturn(PROJECT_ID);

      try {
        GcpObservability observability =
            GcpObservability.grpcInit(
                sink, mockConfig, channelInterceptorFactory, serverInterceptorFactory);
        observability.registerStackDriverExporter(PROJECT_ID, CUSTOM_TAGS);

        Server server =
            ServerBuilder.forPort(0)
                .addService(new ObservabilityTestHelper.SimpleServiceImpl())
                .build()
                .start();
        int port = cleanupRule.register(server).getPort();
        SimpleServiceGrpc.SimpleServiceBlockingStub stub =
            SimpleServiceGrpc.newBlockingStub(
                cleanupRule.register(
                    ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build()));
        assertThat(ObservabilityTestHelper.makeUnaryRpcViaClientStub("buddy", stub))
            .isEqualTo("Hello buddy");
        // Adding sleep to ensure traces are exported before querying cloud tracing backend
        TimeUnit.SECONDS.sleep(10);

        TraceServiceClient traceServiceClient = TraceServiceClient.create();
        String traceFilter =
            String.format(
                "span:Sent.grpc.testing.SimpleService +%s:%s", CUSTOM_TAG_KEY, CUSTOM_TAG_VALUE);
        String traceOrder = "start";
        // Restrict time to last 1 minute
        long startMillis = System.currentTimeMillis() - ((60 * 1) * 1000);
        ListTracesRequest traceRequest =
            ListTracesRequest.newBuilder()
                .setProjectId(PROJECT_ID)
                .setStartTime(Timestamps.fromMillis(startMillis))
                .setEndTime(Timestamps.fromMillis(System.currentTimeMillis()))
                .setFilter(traceFilter)
                .setOrderBy(traceOrder)
                .build();
        ListTracesPagedResponse traceResponse = traceServiceClient.listTraces(traceRequest);
        assertThat(traceResponse.iterateAll()).isNotEmpty();
        List<String> traceIdList = new ArrayList<>();
        for (Trace t : traceResponse.iterateAll()) {
          traceIdList.add(t.getTraceId());
        }

        for (String traceId : traceIdList) {
          // This checks Cloud trace for the new trace that was just created.
          GetTraceRequest getTraceRequest =
              GetTraceRequest.newBuilder().setProjectId(PROJECT_ID).setTraceId(traceId).build();
          Trace trace = traceServiceClient.getTrace(getTraceRequest);
          assertThat(trace.getSpansList()).hasSize(3);
          for (TraceSpan span : trace.getSpansList()) {
            assertThat(span.getName()).contains("grpc.testing.SimpleService.UnaryRpc");
            assertThat(span.getLabelsMap().get(CUSTOM_TAG_KEY)).isEqualTo(CUSTOM_TAG_VALUE);
          }
        }
        observability.close();
      } catch (IOException | InterruptedException e) {
        throw new AssertionError("Exception while testing traces", e);
      }
    }
  }
}
