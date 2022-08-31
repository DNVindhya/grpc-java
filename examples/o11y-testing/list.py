#!/usr/bin/python3

from google.cloud import monitoring_v3
from google.cloud import trace_v1
from google.cloud import logging_v2

import time
from datetime import datetime, timedelta, timezone
from google.protobuf.timestamp_pb2 import Timestamp

now = time.time()
seconds = int(now)
nanos = int((now - seconds) * 10**9)
start_time = datetime.now(timezone.utc) - timedelta(minutes=5) # last 5 minutes
start_seconds = int(start_time.timestamp())

project = 'stanleycheung-gke2-dev'
project_name = f"projects/{project}"


# Metrics
print("--> Metrics")

metric_client = monitoring_v3.MetricServiceClient()

interval = monitoring_v3.TimeInterval({
    "end_time": {"seconds": seconds, "nanos": nanos},
    "start_time": {"seconds": start_seconds, "nanos": nanos},
})

results = metric_client.list_time_series(
    name=project_name,
    filter='metric.type = "custom.googleapis.com/my_metric"',
    interval=interval,
)
for result in results:
    print("----> Found custom my_metric:")
    for point in result.points:
        print('  '+point.interval.end_time.strftime('%c'))

print('')

results = metric_client.list_time_series(
    name=project_name,
    filter='metric.type = "custom.googleapis.com/opencensus/grpc.io/client/roundtrip_latency"',
    interval=interval,
)
for result in results:
    print("----> Found grpc.io/client/roundtrip_latency:")
    for point in result.points:
        print('  '+point.interval.end_time.strftime('%c'))

print('')


# Trace
print("--> Trace")

trace_client = trace_v1.TraceServiceClient()

request = trace_v1.ListTracesRequest(
    project_id=project,
    start_time=Timestamp(seconds=start_seconds)
)

page_result = trace_client.list_traces(request=request)

for response in page_result:
    trace_request = trace_v1.GetTraceRequest(
        project_id=project,
        trace_id=response.trace_id
    )
    trace_response = trace_client.get_trace(request=trace_request)
    print("  {}: {}".format(response.trace_id, trace_response.spans[0].start_time))

print('')


# Logging
print("--> Logging")

logging_client = logging_v2.Client()
logging_client.setup_logging()
logger = logging_client.logger("microservices.googleapis.com%2Fobservability%2Fgrpc")

time_format = "%Y-%m-%dT%H:%M:%S.%f%z"
filter_str = f'timestamp >= "{start_time.strftime(time_format)}"'
for entry in logger.list_entries(filter_ = filter_str):
    timestamp = entry.timestamp.isoformat()
    print("  {}: {}".format(timestamp, entry.payload['event_type']))
