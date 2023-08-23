/*
 * Copyright 2023 The gRPC Authors
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

package io.grpc.opentelemetry;

import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import io.grpc.ClientInterceptor;
import io.grpc.ExperimentalApi;
import io.grpc.opentelemetry.OpenTelemetryState.OpenTelemetryStateBuilder;
import io.grpc.opentelemetry.internal.OpenTelemetryConstants;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.MeterProvider;

@ExperimentalApi("Link for Github issue")
public final class OpenTelemetryModule {

  private static final Supplier<Stopwatch> STOPWATCH_SUPPLIER = new Supplier<Stopwatch>() {
    @Override
    public Stopwatch get() {
      return Stopwatch.createUnstarted();
    }
  };

  private final OpenTelemetry openTelemetry;
  private OpenTelemetryState state;
  private MeterProvider meterProvider;
  private Meter meter;

  private OpenTelemetryModule(OpenTelemetryModuleBuilder builder) {
    this(builder.openTelemetry, builder.state);
  }

  OpenTelemetryModule(OpenTelemetry openTelemetry, OpenTelemetryState state) {
    this.openTelemetry = openTelemetry;
    this.state = state;
  }

  MeterProvider getMeterProvider() {
    // TODO: Check for valid value or use noop meter provider
    return openTelemetry.getMeterProvider();
  }

  Meter getMeter() {
    return openTelemetry.getMeterProvider().get(OpenTelemetryConstants.INSTRUMENTATION_SCOPE);
  }

  public ClientInterceptor getClientInterceptor() {
    OpenTelemetryMetricsModule openTelemetryMetricsModule =
        new OpenTelemetryMetricsModule(
            STOPWATCH_SUPPLIER,
            state);
    return openTelemetryMetricsModule.getClientInterceptor();
  }

  public static class OpenTelemetryModuleBuilder {
    private OpenTelemetry openTelemetry;
    private OpenTelemetryState state;
    private MeterProvider meterProvider;
    private Meter meter;

    public OpenTelemetryModuleBuilder sdk(OpenTelemetry openTelemetrySdk) {
      this.openTelemetry = openTelemetrySdk;
      return this;
    }

    public OpenTelemetryModule build() {
      validate();
      createInstruments(openTelemetry);
      return new OpenTelemetryModule(this);
    }

    private void validate() {
      if (openTelemetry == null) {
        openTelemetry = OpenTelemetry.noop();
      }
    }

    void createInstruments(OpenTelemetry openTelemetryInstance) {
      meterProvider = openTelemetryInstance.getMeterProvider();
      meter = meterProvider.get(OpenTelemetryConstants.INSTRUMENTATION_SCOPE);

      OpenTelemetryStateBuilder builder = new OpenTelemetryStateBuilder();

      builder.clientCallDurationCounter(
          meter.histogramBuilder(OpenTelemetryConstants.CLIENT_CALL_DURATION)
              .setUnit("latency buckets")
              .build());

      builder.clientAttemptCountCounter(
          meter.counterBuilder(OpenTelemetryConstants.CLIENT_ATTEMPT_COUNT_INSTRUMENT_NAME)
              .setUnit("{attempt}")
              .build());

      builder.clientAttemptDurationCounter(
          meter.histogramBuilder(OpenTelemetryConstants.CLIENT_ATTEMPT_DURATION_INSTRUMENT_NAME)
              .setUnit("latency buckets")
              .build());

      builder.clientTotalSentCompressedMessageSizeCounter(
          meter.histogramBuilder(
                  OpenTelemetryConstants.CLIENT_ATTEMPT_SENT_TOTAL_COMPRESSED_MESSAGE_SIZE)
              .setUnit("size buckets")
              .ofLongs()
              .build());

      builder.clientTotalReceivedCompressedMessageSizeCounter(
          meter.histogramBuilder(
                  OpenTelemetryConstants.CLIENT_ATTEMPT_RECV_TOTAL_COMPRESSED_MESSAGE_SIZE)
              .setUnit("size buckets")
              .ofLongs()
              .build());

      builder.serverCallCountCounter(
          meter.counterBuilder(OpenTelemetryConstants.SERVER_CALL_COUNT)
              .setUnit("{call}")
              .build());

      builder.serverCallDurationCounter(
          meter.histogramBuilder(OpenTelemetryConstants.SERVER_CALL_DURATION)
              .setUnit("latency buckets")
              .build());

      builder.serverTotalSentCompressedMessageSizeCounter(
          meter.histogramBuilder(
                  OpenTelemetryConstants.SERVER_CALL_SENT_TOTAL_COMPRESSED_MESSAGE_SIZE)
              .setUnit("size buckets")
              .ofLongs()
              .build());

      builder.serverTotalReceivedCompressedMessageSizeCounter(
          meter.histogramBuilder(
              OpenTelemetryConstants.SERVER_CALL_RECV_TOTAL_COMPRESSED_MESSAGE_SIZE)
              .setUnit("size buckets")
              .ofLongs()
              .build());

      this.state = builder.build();
    }
  }
}