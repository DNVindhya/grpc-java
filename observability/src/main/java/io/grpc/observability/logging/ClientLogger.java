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

package io.grpc.observability.logging;

import io.grpc.observabilitylog.v1.GrpcLogRecord;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Sample use custom CloudLoggingHandler. */
public class ClientLogger {
  private static Logger logger = Logger.getLogger("Observability_Logger");

  /** Takes destination project id as an argument. */
  public static void main(String[] args) throws Exception {
    String projectId = args[0] != null ? args[0] : "project-id";
    String logName = "mt-test-log";

    // Registering custom logging handler
    logger.addHandler(new CloudLoggingHandler(Level.ALL, logName, projectId));

    GrpcLogRecord logProto =
        GrpcLogRecord.newBuilder()
            .setRpcId(1001)
            .setServiceName("foo")
            .setMethodName("bar")
            .build();

    System.out.println("Writing custom record to cloud logging");

    LogRecordExtension record = new LogRecordExtension(Level.INFO, logProto);

    System.out.println("Using custom logger to write log to cloud logging");

    // Using custom logging handler to log LogRecordExtension record
    logger.log(record);

    System.out.println("Successfully logged");
  }
}
