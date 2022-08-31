#!/bin/bash -x

IMAGENAME=o11y-examples
TAG=1.50.0-SNAPSHOT_0823
PROJECTID=`gcloud config get-value project`

echo Building ${IMAGENAME}:${TAG}

docker build --build-arg builddate="$(date)" --no-cache -t o11y/${IMAGENAME}:${TAG} -f Dockerfile-o11y.common .

docker tag o11y/${IMAGENAME}:${TAG} gcr.io/${PROJECTID}/${IMAGENAME}:${TAG}

docker push gcr.io/${PROJECTID}/${IMAGENAME}:${TAG}
