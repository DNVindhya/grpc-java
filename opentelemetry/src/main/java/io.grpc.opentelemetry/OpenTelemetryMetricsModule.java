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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientStreamTracer;
import io.grpc.ClientStreamTracer.StreamInfo;
import io.grpc.Deadline;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerStreamTracer;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.opentelemetry.internal.OpenTelemetryConstants;
import io.opentelemetry.api.common.AttributeKey;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

public class OpenTelemetryMetricsModule {

  private static final Logger logger = Logger.getLogger(OpenTelemetryMetricsModule.class.getName());
  private static final double NANOS_PER_MILLI = TimeUnit.MILLISECONDS.toNanos(1);
  private final OpenTelemetryState state;
  private final Supplier<Stopwatch> stopwatchSupplier;

  OpenTelemetryMetricsModule(Supplier<Stopwatch> stopwatchSupplier, OpenTelemetryState state) {
    this.state = checkNotNull(state, "state");
    this.stopwatchSupplier = checkNotNull(stopwatchSupplier, "stopwatchSupplier");
  }

  ServerStreamTracer.Factory getServerTracerFactory() {
    return new ServerTracerFactory();
  }

  ClientInterceptor getClientInterceptor() {
    return new MetricsClientInterceptor();
  }

  private static final class ClientTracer extends ClientStreamTracer {

    @Nullable private static final AtomicLongFieldUpdater<ClientTracer> outboundWireSizeUpdater;
    @Nullable private static final AtomicLongFieldUpdater<ClientTracer> inboundWireSizeUpdater;

    /*
     * When using Atomic*FieldUpdater, some Samsung Android 5.0.x devices encounter a bug in their
     * JDK reflection API that triggers a NoSuchFieldException. When this occurs, we fallback to
     * (potentially racy) direct updates of the volatile variables.
     */
    static {
      AtomicLongFieldUpdater<ClientTracer> tmpOutboundWireSizeUpdater;
      AtomicLongFieldUpdater<ClientTracer> tmpInboundWireSizeUpdater;
      try {
        tmpOutboundWireSizeUpdater =
            AtomicLongFieldUpdater.newUpdater(ClientTracer.class, "outboundWireSize");
        tmpInboundWireSizeUpdater =
            AtomicLongFieldUpdater.newUpdater(ClientTracer.class, "inboundWireSize");
      } catch (Throwable t) {
        logger.log(Level.SEVERE, "Creating atomic field updaters failed", t);
        tmpOutboundWireSizeUpdater = null;
        tmpInboundWireSizeUpdater = null;
      }
      outboundWireSizeUpdater = tmpOutboundWireSizeUpdater;
      inboundWireSizeUpdater = tmpInboundWireSizeUpdater;
    }

    final Stopwatch stopwatch;
    final CallAttemptsTracerFactory attemptsState;
    final AtomicBoolean inboundReceivedOrClosed = new AtomicBoolean();
    final OpenTelemetryMetricsModule module;
    final StreamInfo info;
    final String fullMethodName;
    volatile long outboundWireSize;
    volatile long inboundWireSize;
    long attemptNanos;
    Code statusCode;

    ClientTracer(CallAttemptsTracerFactory attemptsState, OpenTelemetryMetricsModule module,
        StreamInfo info, String fullMethodName) {
      this.attemptsState = attemptsState;
      this.module = module;
      this.info = info;
      this.fullMethodName = fullMethodName;
      this.stopwatch = module.stopwatchSupplier.get().start();
    }

    @Override
    public void streamCreated(Attributes transportAttrs, Metadata headers) {
      // evaluate if we need to use implicit/explicit Context
    }

    @Override
    @SuppressWarnings("NonAtomicVolatileUpdate")
    public void outboundWireSize(long bytes) {
      if (outboundWireSizeUpdater != null) {
        outboundWireSizeUpdater.getAndAdd(this, bytes);
      } else {
        outboundWireSize += bytes;
      }
    }

    @Override
    @SuppressWarnings("NonAtomicVolatileUpdate")
    public void inboundWireSize(long bytes) {
      if (inboundWireSizeUpdater != null) {
        inboundWireSizeUpdater.getAndAdd(this, bytes);
      } else {
        inboundWireSize += bytes;
      }
    }


    @Override
    public void streamClosed(Status status) {
      stopwatch.stop();
      attemptNanos = stopwatch.elapsed(TimeUnit.NANOSECONDS);
      Deadline deadline = info.getCallOptions().getDeadline();
      statusCode = status.getCode();
      if (statusCode == Code.CANCELLED && deadline != null) {
        if (deadline.isExpired()) {
          statusCode = Code.DEADLINE_EXCEEDED;
        }
      }
      attemptsState.attemptEnded();
      if (inboundReceivedOrClosed.compareAndSet(false, true)) {
        recordFinishedAttempt();
      }
    }

    void recordFinishedAttempt() {
      io.opentelemetry.api.common.Attributes attributes = io.opentelemetry.api.common.Attributes.of(
          AttributeKey.stringKey(OpenTelemetryConstants.METHOD_KEY), fullMethodName,
          AttributeKey.stringKey(OpenTelemetryConstants.STATUS_KEY), statusCode.toString());
      // record latency, message size
      // add status tag as well as method
      // TODO : figure out how to get target, method name and peer address

      module.state.clientAttemptDuration.record(attemptNanos / NANOS_PER_MILLI, attributes);
      // Aggregation for histograms will be specified in sdk, we would use explicit
      // aggregation over exponential aggregation because of well-defined bucket sizes for
      // comparison
      module.state.clientTotalSentCompressedMessageSize.record(outboundWireSize, attributes);
      module.state.serverTotalReceivedCompressedMessageSize.record(inboundWireSize, attributes);
    }
  }

  @VisibleForTesting
  static final class CallAttemptsTracerFactory extends ClientStreamTracer.Factory {
    ClientTracer inboundMetricTracer;
    private final OpenTelemetryMetricsModule module;
    private final Stopwatch attemptStopwatch;
    private final Stopwatch callStopWatch;
    @GuardedBy("lock")
    private boolean callEnded;
    private final String fullMethodName;
    private Status status;
    private long callLatencyNanos;
    private final Object lock = new Object();
    private static final AtomicLongFieldUpdater<CallAttemptsTracerFactory> attemptsPerCallUpdater;

    static {
      AtomicLongFieldUpdater<CallAttemptsTracerFactory> tmpAttemptsPerCallUpdater;
      try {
        tmpAttemptsPerCallUpdater =
            AtomicLongFieldUpdater.newUpdater(CallAttemptsTracerFactory.class, "attemptsPerCall");
      } catch (Throwable t) {
        logger.log(Level.SEVERE, "Creating atomic field updaters failed", t);
        tmpAttemptsPerCallUpdater = null;
      }
      attemptsPerCallUpdater = tmpAttemptsPerCallUpdater;
    }
    private volatile Long attemptsPerCall;
    @GuardedBy("lock")
    private int activeStreams;
    @GuardedBy("lock")
    private boolean finishedCallToBeRecorded;

    CallAttemptsTracerFactory(OpenTelemetryMetricsModule module, String fullMethodName) {
      this.module = checkNotNull(module, "module");
      this.fullMethodName = checkNotNull(fullMethodName, "fullMethodName");
      this.attemptStopwatch = module.stopwatchSupplier.get();
      this.callStopWatch = module.stopwatchSupplier.get().start();
      // Set client method
      // Record grpc.client.attempt.started = 1
      // TODO: figure out what to add in OTel context or to use implicit context
      // TODO: figure out how to add target name
      io.opentelemetry.api.common.Attributes attributes = io.opentelemetry.api.common.Attributes.of(
          AttributeKey.stringKey(OpenTelemetryConstants.METHOD_KEY), fullMethodName);
      // Record here in case mewClientStreamTracer() would never be called.
      module.state.clientAttemptCount.add(1, attributes);
    }

    @Override
    public ClientStreamTracer newClientStreamTracer(StreamInfo info, Metadata metadata) {
      synchronized (lock) {
        if (finishedCallToBeRecorded) {
          // This can be the case when the call is cancelled but a retry attempt is created.
          return new ClientStreamTracer() {
          };
        }
        if (++activeStreams == 1 && attemptStopwatch.isRunning()) {
          attemptStopwatch.stop();
        }
      }
      if (attemptsPerCallUpdater.get(this) > 0) {
        // Add target as an attribute
        io.opentelemetry.api.common.Attributes attributes =
            io.opentelemetry.api.common.Attributes.of(
            AttributeKey.stringKey(OpenTelemetryConstants.METHOD_KEY), fullMethodName);
        module.state.clientAttemptCount.add(1, attributes);
      }
      attemptsPerCallUpdater.getAndIncrement(this);
      return new ClientTracer(this, module, info, fullMethodName);
    }

    void attemptEnded() {
      boolean shouldRecordFinishedCall = false;
      synchronized (lock) {
        if (--activeStreams == 0) {
          attemptStopwatch.start();
          if (callEnded && !finishedCallToBeRecorded) {
            shouldRecordFinishedCall = true;
            finishedCallToBeRecorded = true;
          }
        }
      }
      if (shouldRecordFinishedCall) {
        recordFinishedCall();
      }
    }

    void callEnded(Status status) {
      callStopWatch.stop();
      this.status = status;
      boolean shouldRecordFinishedCall = false;
      synchronized (lock) {
        if (callEnded) {
          // TODO(https://github.com/grpc/grpc-java/issues/7921): this shouldn't happen
          return;
        }
        callEnded = true;
        if (activeStreams == 0 && !finishedCallToBeRecorded) {
          shouldRecordFinishedCall = true;
          finishedCallToBeRecorded = true;
        }
      }
      if (shouldRecordFinishedCall) {
        recordFinishedCall();
      }
    }

    void recordFinishedCall() {
      if (attemptsPerCallUpdater.get(this) == 0) {
        ClientTracer tracer = new ClientTracer(this, module, null, fullMethodName);
        tracer.attemptNanos = attemptStopwatch.elapsed(TimeUnit.NANOSECONDS);
        tracer.statusCode = status.getCode();
        tracer.recordFinishedAttempt();
      } else if (inboundMetricTracer != null) {
        // activeStreams has been decremented to 0 by attemptEnded(),
        // so inboundMetricTracer.statusCode is guaranteed to be assigned already.
        inboundMetricTracer.recordFinishedAttempt();
      }
      callLatencyNanos = callStopWatch.elapsed(TimeUnit.NANOSECONDS);
      io.opentelemetry.api.common.Attributes attributes
          = io.opentelemetry.api.common.Attributes.of(
          AttributeKey.stringKey(OpenTelemetryConstants.METHOD_KEY), fullMethodName,
          AttributeKey.stringKey(OpenTelemetryConstants.STATUS_KEY), status.getCode().toString());
      module.state.clientCallDuration.record(callLatencyNanos / NANOS_PER_MILLI, attributes);
    }
  }

  private static final class ServerTracer extends ServerStreamTracer {
    @Nullable private static final AtomicIntegerFieldUpdater<ServerTracer> streamClosedUpdater;
    @Nullable private static final AtomicLongFieldUpdater<ServerTracer> outboundWireSizeUpdater;
    @Nullable private static final AtomicLongFieldUpdater<ServerTracer> inboundWireSizeUpdater;

    /*
     * When using Atomic*FieldUpdater, some Samsung Android 5.0.x devices encounter a bug in their
     * JDK reflection API that triggers a NoSuchFieldException. When this occurs, we fallback to
     * (potentially racy) direct updates of the volatile variables.
     */
    static {
      AtomicIntegerFieldUpdater<ServerTracer> tmpStreamClosedUpdater;
      AtomicLongFieldUpdater<ServerTracer> tmpOutboundWireSizeUpdater;
      AtomicLongFieldUpdater<ServerTracer> tmpInboundWireSizeUpdater;
      try {
        tmpStreamClosedUpdater =
            AtomicIntegerFieldUpdater.newUpdater(ServerTracer.class, "streamClosed");
        tmpOutboundWireSizeUpdater =
            AtomicLongFieldUpdater.newUpdater(ServerTracer.class, "outboundWireSize");
        tmpInboundWireSizeUpdater =
            AtomicLongFieldUpdater.newUpdater(ServerTracer.class, "inboundWireSize");
      } catch (Throwable t) {
        logger.log(Level.SEVERE, "Creating atomic field updaters failed", t);
        tmpStreamClosedUpdater = null;
        tmpOutboundWireSizeUpdater = null;
        tmpInboundWireSizeUpdater = null;
      }
      streamClosedUpdater = tmpStreamClosedUpdater;
      outboundWireSizeUpdater = tmpOutboundWireSizeUpdater;
      inboundWireSizeUpdater = tmpInboundWireSizeUpdater;
    }

    private final OpenTelemetryMetricsModule module;
    private final String fullMethodName;
    private volatile int streamClosed;
    private final Stopwatch stopwatch;
    private volatile long outboundWireSize;
    private volatile long inboundWireSize;

    ServerTracer(OpenTelemetryMetricsModule module, String fullMethodName) {
      this.module = checkNotNull(module, "module");
      this.fullMethodName = fullMethodName;
      this.stopwatch = module.stopwatchSupplier.get().start();
    }

    @Override
    @SuppressWarnings("NonAtomicVolatileUpdate")
    public void outboundWireSize(long bytes) {
      if (outboundWireSizeUpdater != null) {
        outboundWireSizeUpdater.getAndAdd(this, bytes);
      } else {
        outboundWireSize += bytes;
      }
    }

    @Override
    @SuppressWarnings("NonAtomicVolatileUpdate")
    public void inboundWireSize(long bytes) {
      if (inboundWireSizeUpdater != null) {
        inboundWireSizeUpdater.getAndAdd(this, bytes);
      } else {
        inboundWireSize += bytes;
      }
    }

    @Override
    public void streamClosed(Status status) {
      if (streamClosedUpdater != null) {
        if (streamClosedUpdater.getAndSet(this, 1) != 0) {
          return;
        }
      } else {
        if (streamClosed != 0) {
          return;
        }
        streamClosed = 1;
      }
      stopwatch.stop();
      long elapsedTimeNanos = stopwatch.elapsed(TimeUnit.NANOSECONDS);
      io.opentelemetry.api.common.Attributes attributes
          = io.opentelemetry.api.common.Attributes.of(
          AttributeKey.stringKey(OpenTelemetryConstants.METHOD_KEY), fullMethodName,
          AttributeKey.stringKey(OpenTelemetryConstants.STATUS_KEY), status.getCode().toString());
      module.state.serverCallDuration.record(elapsedTimeNanos / NANOS_PER_MILLI, attributes);
    }
  }

  @VisibleForTesting
  final class ServerTracerFactory extends ServerStreamTracer.Factory {
    @Override
    public ServerStreamTracer newServerStreamTracer(String fullMethodName, Metadata headers) {
      return new ServerTracer(OpenTelemetryMetricsModule.this, fullMethodName);
    }
  }

  @VisibleForTesting
  final class MetricsClientInterceptor implements ClientInterceptor {
    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
      final CallAttemptsTracerFactory tracerFactory = new CallAttemptsTracerFactory(
          OpenTelemetryMetricsModule.this, method.getFullMethodName());
      ClientCall<ReqT, RespT> call =
          next.newCall(method, callOptions.withStreamTracerFactory(tracerFactory));
      return new SimpleForwardingClientCall<ReqT, RespT>(call) {
        @Override
        public void start(Listener<RespT> responseListener, Metadata headers) {
          delegate().start(
              new SimpleForwardingClientCallListener<RespT>(responseListener) {
                @Override
                public void onClose(Status status, Metadata trailers) {
                  tracerFactory.callEnded(status);
                  super.onClose(status, trailers);
                }
              },
              headers);
        }
      };
    }
  }
}
