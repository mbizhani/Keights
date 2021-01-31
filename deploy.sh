#!/bin/bash

source .env

./mvnw clean package
RES="$?"
if [ "$RES" != "0" ]; then
  exit 1
fi

docker build -t "${REG_URL}/${IMAGE_NAME}:${IMAGE_TAG}" .
RES="$?"
if [ "$RES" != "0" ]; then
  exit 1
fi

if [ "${REG_USER}" ]; then
  docker login -u "${REG_USER}" -p "${REG_PASS}" "${REG_URL}"
fi
docker push "${REG_URL}/${IMAGE_NAME}:${IMAGE_TAG}"
RES="$?"
if [ "$RES" != "0" ]; then
  exit 1
fi

kubectl create namespace keights || true

PODS="$(kubectl get pod -l app=keights-coredns | wc -l)"
echo "PODS = ${PODS}"
if [ "${PODS}" == "0" ]; then
  kubectl apply -f k8s-files/keights-coredns.yml
else
  kubectl rollout restart deployment keights-coredns
fi