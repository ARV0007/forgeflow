# ForgeFlow on Kubernetes

The full topology from the architecture diagram, as manifests:

| Workload | Kind | Replicas | Why that many |
|---|---|---|---|
| gateway | Deployment + Ingress | 2 | stateless edge |
| api | Deployment | 1 | previews and live logs are held in memory per instance (see 20-api.yaml) |
| worker | Deployment + HPA | 1–3 | consumes `code.generated` as group `indexer`; more than 3 = idle, one per partition |
| postgres, kafka, minio, qdrant | StatefulSet | 1 | dev-grade; production = managed service or operator |
| redis, zipkin | Deployment | 1 | |

## Run it on kind

```bash
kind create cluster --name forgeflow
# ingress-nginx for kind: https://kind.sigs.k8s.io/docs/user/ingress/

docker build -t forgeflow:latest .
docker build -t forgeflow-gateway:latest ./gateway
kind load docker-image forgeflow:latest forgeflow-gateway:latest --name forgeflow

cp deploy/k8s/11-secret.example.yaml deploy/k8s/11-secret.yaml   # then fill it in
kubectl apply -k deploy/k8s
kubectl -n forgeflow get pods -w
```

Then add `127.0.0.1 forgeflow.local` to `/etc/hosts` and open http://forgeflow.local.

## What to look at

```bash
kubectl -n forgeflow logs deploy/worker -f        # "indexed project N ..." after each AI run
kubectl -n forgeflow port-forward svc/zipkin 9411 # one trace: gateway -> api -> kafka -> worker
kubectl -n forgeflow scale deploy/worker --replicas=3   # watch the partitions rebalance in the worker logs
```
