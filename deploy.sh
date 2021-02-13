#!/bin/bash

source .env

./mvnw clean package
RES="$?"
if [ "$RES" != "0" ]; then
  exit 1
fi

[ "${PUSH_REG_URL}" ] && SEP="/" || SEP=""
docker build -t "${PUSH_REG_URL}${SEP}${IMAGE_NAME}:${IMAGE_TAG}" .
RES="$?"
if [ "$RES" != "0" ]; then
  exit 1
fi

if [ "${PUSH_REG_USER}" ]; then
  docker login -u "${PUSH_REG_USER}" -p "${PUSH_REG_PASS}" "${PUSH_REG_URL}"
fi
docker push "${PUSH_REG_URL}${SEP}${IMAGE_NAME}:${IMAGE_TAG}"
RES="$?"
if [ "$RES" != "0" ]; then
  exit 1
fi

kubectl create namespace keights || true

PODS="$(kubectl get pod -l app=keights-coredns -n keights | wc -l)"
echo "PODS = ${PODS}"
if [ "${PODS}" == "0" ]; then
  eval "cat <<EOF
$(<k8s-files/keights-coredns.yml)
EOF
" | kubectl apply -f -
else
  kubectl rollout restart deployment keights-coredns -n keights
fi