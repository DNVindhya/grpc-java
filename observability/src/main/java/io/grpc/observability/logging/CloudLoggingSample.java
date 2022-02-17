/*
 * Copyright 2016 Google Inc.
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

// [START logging_quickstart]
/*import com.google.api.gax.paging.Page;*/
import com.google.cloud.MonitoredResource;
import com.google.cloud.logging.LogEntry;
import com.google.cloud.logging.Logging;
import com.google.cloud.logging.LoggingOptions;
import com.google.cloud.logging.Payload.StringPayload;
import com.google.cloud.logging.Severity;
import java.util.Collections;

public class CloudLoggingSample {

  /** Expects a new or existing Cloud log name as the first argument. */
  public static void main(String... args) throws Exception {
    // The name of the log to write to
    String projectId = args[0] != null ? args[0] : "project-id";
    String logName = "my-test-log"; // "my-log";
    String textPayload = "Hello, world!";

    // Instantiates a client
    /*    try (Logging logging = LoggingOptions.getDefaultInstance().getService()) {*/
    LoggingOptions options = LoggingOptions.newBuilder().setProjectId(projectId).build();

    try (Logging logging = options.getService()) {

      LogEntry entry =
          LogEntry.newBuilder(StringPayload.of(textPayload))
              .setSeverity(Severity.ERROR)
              .setLogName(logName)
              .setResource(MonitoredResource.newBuilder("global").build())
              .build();

      // Writes the log entry asynchronously
      System.out.println("Writing log to cloud logging");
      logging.write(Collections.singleton(entry));
      System.out.println("Completed logging.write()");
/*      Page<LogEntry> logEntries = logging.listLogEntries();*/
    } catch (Exception ex) {
      throw ex;
    }
    System.out.printf("Logged: %s%n", textPayload);
  }
}
