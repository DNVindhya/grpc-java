#!/bin/bash -x

# now delete our deployment of service and client
kubectl delete -f o11y-testing/gke-deployment.yaml