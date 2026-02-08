
# 1️⃣ Chart structure (unchanged, reused)

```text
observability-service/
├── Chart.yaml
├── values.yaml
└── templates/
    ├── _helpers.tpl
    ├── deployment.yaml
    ├── service.yaml
    ├── ingress.yaml
    ├── hpa.yaml
    ├── vault-agent-configmap.yaml
    ├── csi-config.yaml
    └── csi-secret-provider.yaml
```

---

# 2️⃣ Chart.yaml

```yaml
apiVersion: v2
name: observability-service
description: Observability Spring Boot service with Postgres and Redis
type: application
version: 0.1.0
appVersion: "1.0.0"
```

---

# 3️⃣ values.yaml (most important)

This is where **reuse really pays off**.

```yaml
namespace: observability

replicaCount: 2

image:
  repository: release-container-registry.home.net/observability/observability-service
  tag: 1.0.0
  pullPolicy: IfNotPresent

ports:
  http: 8080

resources:
  requests:
    cpu: 500m
    memory: 1Gi
  limits:
    cpu: 1000m
    memory: 2Gi

autoscaling:
  enabled: true
  minReplicas: 2
  maxReplicas: 10
  averageRelativeCPU: 70
  averageRelativeMemory: 80

service:
  type: ClusterIP
  ports:
    - name: http
      port: 80
      targetPort: 8080

ingress:
  web:
    enabled: true
    ingressClassName: nginx
    annotations:
      kubernetes.io/ingress.allow-http: "false"
      nginx.ingress.kubernetes.io/ssl-redirect: "true"
    hosts:
      - name: observability-api.yourdomain.com
        tls:
          enabled: true
          secretName: observability-tls
    path: /
    pathType: Prefix

livenessProbe:
  enabled: true
  path: /actuator/health/liveness
  port: 8080
  scheme: HTTP
  initialDelaySeconds: 60
  timeoutSeconds: 5
  periodSeconds: 10
  failureThreshold: 3

readinessProbe:
  enabled: true
  path: /actuator/health/readiness
  port: 8080
  scheme: HTTP
  initialDelaySeconds: 30
  timeoutSeconds: 3
  periodSeconds: 5
  failureThreshold: 3

# PostgreSQL
data:
  metadataConnection:
    protocol: postgresql
    host: postgres.observability.svc.cluster.local
    port: 5432
    db: observability
    user: observability
    sslmode: require

# Redis
redis:
  host: redis.observability.svc.cluster.local
  port: 6379

vault:
  enabled: true
  role: observability-service
  resource: https://vault.azure.net
  secrets:
    postgres:
      SPRING_DATASOURCE_PASSWORD: observability/postgres
    redis:
      REDIS_PASSWORD: observability/redis
    generic:
      APPLICATIONINSIGHTS_CONNECTION_STRING: observability/appinsights

csi:
  enabled: true
  csiMount: /etc/secrets
  parameters:
    tenantId: "MaverickCloud.onmicrosoft.com"
    keyvaultName: observability-kv
```

# 4️⃣ templates/deployment.yaml

This is **reused**, only names and envs change.

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "app.fullname" . }}
  namespace: {{ .Values.namespace }}
spec:
  replicas: {{ .Values.replicaCount }}
  selector:
    matchLabels:
      app: {{ include "app.name" . }}
  template:
    metadata:
      labels:
        app: {{ include "app.name" . }}
    spec:
      serviceAccountName: {{ include "vault.serviceAccountName" . }}
      containers:
        - name: observability-service
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          ports:
            - name: http
              containerPort: {{ .Values.ports.http }}

          env:
            - name: SPRING_PROFILES_ACTIVE
              value: "prod"
            - name: SPRING_DATASOURCE_URL
              value: "jdbc:postgresql://{{ .Values.data.metadataConnection.host }}:{{ .Values.data.metadataConnection.port }}/{{ .Values.data.metadataConnection.db }}?sslmode={{ .Values.data.metadataConnection.sslmode }}"
            - name: SPRING_DATASOURCE_USERNAME
              value: {{ .Values.data.metadataConnection.user }}
            - name: REDIS_HOST
              value: {{ .Values.redis.host }}

          envFrom:
            - secretRef:
                name: vault-secrets

          resources:
{{ toYaml .Values.resources | nindent 12 }}

          livenessProbe:
            httpGet:
              path: {{ .Values.livenessProbe.path }}
              port: {{ .Values.livenessProbe.port }}
            initialDelaySeconds: {{ .Values.livenessProbe.initialDelaySeconds }}

          readinessProbe:
            httpGet:
              path: {{ .Values.readinessProbe.path }}
              port: {{ .Values.readinessProbe.port }}
            initialDelaySeconds: {{ .Values.readinessProbe.initialDelaySeconds }}
```

---

# 5️⃣ templates/service.yaml

```yaml
apiVersion: v1
kind: Service
metadata:
  name: {{ include "app.fullname" . }}
  namespace: {{ .Values.namespace }}
spec:
  type: {{ .Values.service.type }}
  selector:
    app: {{ include "app.name" . }}
  ports:
{{ toYaml .Values.service.ports | nindent 4 }}
```

---

# 6️⃣ templates/ingress.yaml

```yaml
{{- if .Values.ingress.web.enabled }}
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: {{ include "app.fullname" . }}-ingress
  namespace: {{ .Values.namespace }}
  annotations:
{{ toYaml .Values.ingress.web.annotations | nindent 4 }}
spec:
  ingressClassName: {{ .Values.ingress.web.ingressClassName }}
  tls:
    - hosts:
        - {{ (index .Values.ingress.web.hosts 0).name }}
      secretName: {{ (index .Values.ingress.web.hosts 0).tls.secretName }}
  rules:
    - host: {{ (index .Values.ingress.web.hosts 0).name }}
      http:
        paths:
          - path: {{ .Values.ingress.web.path }}
            pathType: {{ .Values.ingress.web.pathType }}
            backend:
              service:
                name: {{ include "app.fullname" . }}
                port:
                  number: 80
{{- end }}
```

---

# 7️⃣ templates/hpa.yaml

```yaml
{{- if .Values.autoscaling.enabled }}
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: {{ include "app.fullname" . }}
  namespace: {{ .Values.namespace }}
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: {{ include "app.fullname" . }}
  minReplicas: {{ .Values.autoscaling.minReplicas }}
  maxReplicas: {{ .Values.autoscaling.maxReplicas }}
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: {{ .Values.autoscaling.averageRelativeCPU }}
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: {{ .Values.autoscaling.averageRelativeMemory }}
{{- end }}
```

---

# 8️⃣ templates/_helpers.tpl (minimal)

```tpl
{{- define "app.name" -}}
observability-service
{{- end }}

{{- define "app.fullname" -}}
{{ .Release.Name }}-observability-service
{{- end }}

{{- define "vault.serviceAccountName" -}}
workload-identity-sa
{{- end }}
```

---

# ✅ Final comparison

| Aspect                  | Old YAML | Reused Helm  |
| ----------------------- | -------- | ------------ |
| Environment flexibility | ❌        | ✅            |
| Vault + CSI             | ❌        | ✅            |
| HPA                     | Basic    | Advanced     |
| TLS                     | Static   | Configurable |
| Multi-env deploy        | ❌        | ✅            |
| Enterprise ready        | ❌        | ✅            |

---

## One-sentence takeaway

> You now have **one reusable Helm chart** that can deploy *any* Spring Boot service (including `observability-service`) with PostgreSQL, Redis, Vault, autoscaling, and ingress — just by changing `values.yaml`.

If you want next:

* Redis/Postgres **subcharts**
* cert-manager integration
* environment overlays (dev/stage/prod)
* GitOps-ready (ArgoCD / Flux)

Just say the word 🚀


--------------------------------------------------

Lift and Shift from old:

Perfect ask 👍 — this is exactly how you should **lift & shift** a mature Helm chart.

Below I’ll show **ONLY the lines you need to change** (or parameterize) when reusing the existing chart for **`observability-service`**.

Think of this as a **rename + small wiring diff**, not a rewrite.

---

# 1️⃣ Chart.yaml (only changed lines)

```diff
- name: task-state
+ name: observability-service

- description: Task State microservice
+ description: Observability Spring Boot service

- appVersion: "1.0.5"
+ appVersion: "1.0.0"
```

---

# 2️⃣ values.yaml (only changed / new lines)

### Application identity

```diff
+ namespace: observability
```

---

### Image

```diff
- images:
-   task_state:
-     repository: release-container-registry.home.net/maverick/frca-tenant-task-state
-     tag: 1.0.5-snapshot
+ image:
+   repository: release-container-registry.home.net/observability/observability-service
+   tag: 1.0.0
```

---

### Ports

```diff
- ports:
-   taskState: 8080
+ ports:
+   http: 8080
```

---

### Service

```diff
- - name: taskstate
+ - name: http
```

---

### Ingress

```diff
- path: "/FRCA/task-state"
+ path: "/"

- - name: ~
+ - name: observability-api.yourdomain.com
```

```diff
- secretName: task-state-tls
+ secretName: observability-tls
```

---

### PostgreSQL

```diff
- db: task_state
+ db: observability
```

```diff
- user: task_state
+ user: observability
```

---

### Redis

```diff
- REDIS_HOST
+ REDIS_HOST
  value: redis.observability.svc.cluster.local
```

---

### Vault secrets mapping

```diff
- vault:
-   role: task-state
+ vault:
+   role: observability-service
```

```diff
- APPLICATIONINSIGHTS_ROLE_NAME: task-stateservice
+ APPLICATIONINSIGHTS_ROLE_NAME: observability-service
```

---

# 3️⃣ templates/deployment.yaml (only lines to update)

### Deployment name & labels

```diff
- component: task-state
+ component: observability-service
```

```diff
- - name: task-state
+ - name: observability-service
```

---

### Image reference

```diff
- {{ template "task_state_image" . }}
+ {{ .Values.image.repository }}:{{ .Values.image.tag }}
```

---

### Container port

```diff
- containerPort: {{ .Values.ports.taskState }}
+ containerPort: {{ .Values.ports.http }}
```

---

### Spring Boot–specific envs

```diff
+ - name: SPRING_PROFILES_ACTIVE
+   value: "prod"
```

```diff
- value: "jdbc:postgresql://.../task_state"
+ value: "jdbc:postgresql://.../observability"
```

---

# 4️⃣ templates/service.yaml (only changed lines)

```diff
- name: taskstate
+ name: http
```

```diff
- component: task-state
+ component: observability-service
```

---

# 5️⃣ templates/ingress.yaml (only changed lines)

```diff
- name: {{ .Release.Name }}-task-state-ingress
+ name: {{ .Release.Name }}-observability-service-ingress
```

```diff
- service:
-   name: task-state
+ service:
+   name: observability-service
```

```diff
- port:
-   name: taskstate
+ port:
+   name: http
```

---

# 6️⃣ templates/hpa.yaml (only changed lines)

```diff
- name: {{ .Release.Name }}-task-state
+ name: {{ .Release.Name }}-observability-service
```

---

# 7️⃣ templates/_helpers.tpl (key rename points)

```diff
- {{- define "task_state.name" -}}
+ {{- define "app.name" -}}
```

```diff
- task-state
+ observability-service
```

```diff
- {{- define "task_state.fullname" -}}
+ {{- define "app.fullname" -}}
```

---

# 8️⃣ Vault / CSI templates (almost zero changes)

Only **role / secret paths** change:

```diff
- role = "task-state"
+ role = "observability-service"
```

```diff
- secret/runtime/task-state
+ secret/runtime/observability-service
```

---

# 🧠 Mental model to reuse this again

When reusing this chart for **any new Spring Boot service**, you only touch:

1. **values.yaml**

    * image
    * ports
    * ingress host/path
    * DB name/user
    * vault role

2. **helpers.tpl**

    * app name

Everything else stays **identical**.

---

# Full charts:
