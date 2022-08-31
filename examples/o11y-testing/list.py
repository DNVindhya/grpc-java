from google.cloud import monitoring_v3
from google.cloud import trace_v1
from google.cloud import logging_v2

import time

now = time.time()
seconds = int(now)
nanos = int((now - seconds) * 10**9)

project = 'stanleycheung-gke2-dev'
project_name = f"projects/{project}"


# Metrics
print("--> Metrics")

metric_client = monitoring_v3.MetricServiceClient()

interval = monitoring_v3.TimeInterval({
    "end_time": {"seconds": seconds, "nanos": nanos},
    "start_time": {"seconds": (seconds - 1200), "nanos": nanos}, # last 20 minutes
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
print("## Trace")

trace_client = trace_v1.TraceServiceClient()

request = trace_v1.ListTracesRequest(
    project_id=project
)

page_result = trace_client.list_traces(request=request)

for response in page_result:
    print(response)


# Logging
print("## Logging")

logging_client = logging_v2.Client()
logging_client.setup_logging()
logger = logging_client.logger("projects/stanleycheung-gke2-dev/logs/microservices.googleapis.com%2Fobservability%2Fgrpc")

for entry in logger.list_entries():
    timestamp = entry.timestamp.isoformat()
    print("* {}: {}".format(timestamp, entry.payload))
