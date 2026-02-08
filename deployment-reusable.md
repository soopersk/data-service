Below is a **clean, reusable, app-generic version of your chart** with the exact fixes and refactors discussed — but **without changing your architecture** (Vault agent, CSI, WI, HPA, ingress, etc. stay exactly the same).

I’ve kept naming close to your original so this can be dropped in safely.

---

# ✅ values.yaml (generic, reusable)

```yaml
replicaCount: 1

app:
  name: task-state
  component: backend
  jarName: task-state.jar
  containerName: app
  portName: http

autoscaling:
  enabled: true
  minReplicas: 1
  maxReplicas: 3
  averageRelativeCPU: 50
  averageRelativeMemory: 80

resources:
  requests:
    cpu: 250m
    memory: 1000Mi
  limits:
    memory: 1000Mi

imagePullSecrets: []

nameOverride: ""
fullnameOverride: ""

strategy:
  type: RollingUpdate
  rollingUpdate:
    maxSurge: 1
    maxUnavailable: 0

command:
  - "bash"
  - "-c"
  - "{{ include \"appstartCommands\" . }}"

unconditionalCommandList:
  - "{{ .Values.cert.command }}"
  - "{{ .Values.cert.listCommand }}"
  - "exec java {{ include \"javaProperties\" . }} -jar /app/{{ .Values.app.jarName }}"

javaPropertiesList:
  - "-Djavax.net.ssl.trustStore=/etc/secrets/megdp-kafka.jks"
  - "-Djavax.net.ssl.trustStorePassword=$(JKS_PASSWORD)"

extraEnvFrom: []

images:
  app:
    repository: release-container-registry.home.net/maverick/frca-tenant-task-state
    tag: 1.0.5-snapshot
    digest: null
    pullPolicy: IfNotPresent

serviceAccount:
  create: false
  annotations: {}
  name: ""

podAnnotations: {}

annotations: {}

labels:
  azure.workload.identity/use: "true"

securityContext:
  runAsNonRoot: true
  fsGroup: 2000

maverickSecurityContext:
  allowPrivilegeEscalation: false
  readOnlyRootFilesystem: true

service:
  type: ClusterIP
  ports:
    - name: http
      port: 8080
  annotations: {}
  loadBalancerSourceRanges: []

hostAliases: []

priorityClassName: null
nodeSelector: {}
tolerations: []
affinity: {}
topologySpreadConstraints: []
revisionHistoryLimit: null

volumes:
  - name: tmpfs-1
    emptyDir: {}
  - name: home
    emptyDir: {}
  - name: vault-secrets
    emptyDir: {}

volumeMounts:
  - name: tmpfs-1
    mountPath: /tmp
  - name: home
    mountPath: /opt/app
  - name: vault-secrets
    mountPath: /etc/secrets

extraVolumeMounts: []

globalEnv:
  - name: APPLICATIONINSIGHTS_ROLE_NAME
    value: "task-stateservice"

env: []

cert:
  command: "/usr/local/share/create_jks.sh"
  listCommand: "/bin/ls -al /etc/secrets"

vault:
  enabled: true
  vaultAddress: null
  serviceAccount:
    name: workload-identity-sa
  image: container-registry.home.net/hashicorp/vault:1.14.3
  role: null
  resource: null
  secrets:
    generic:
      PASSWORD: null
      JKS_PASSWORD: null
    postgres:
      AIRFLOW_CONN_POSTGRES_DATABASE: null

csi:
  enabled: true
  csiMount: /etc/csi_secrets
  parameters:
    tenantId: "MaverickCloud.onmicrosoft.com"
    userAssignedIdentityID: null
    keyvaultName: null
    cloudName: ""
    objects: null
  secrets:
    generic: {}
    files: {}

registry:
  secretName: null

data:
  metadataConnection:
    user: null
    protocol: postgresql
    host: null
    port: 5432
    db: null
    sslmode: require

web:
  enabled: true
  annotations:
    kubernetes.io/ingress.allow-http: "false"
  path: "/FRCA/task-state"
  pathType: Prefix
  hosts:
    - name: null
  tls:
    enabled: null
    secretName: ""
  ingressClassName: nginx

ports:
  http: 8080

namespace: orchestration-eventservice

livenessProbe:
  port: 8080
  scheme: HTTP
  initialDelaySeconds: 20
  timeoutSeconds: 10
  failureThreshold: 5
  periodSeconds: 60
```

---

# ✅ templates/deployment.yaml

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ .Release.Name }}
  labels:
    app: {{ .Values.app.name }}
    component: {{ .Values.app.component }}
    release: {{ .Release.Name }}
{{- with .Values.labels }}
{{ toYaml . | nindent 4 }}
{{- end }}

spec:
{{- if not .Values.autoscaling.enabled }}
  replicas: {{ .Values.replicaCount }}
{{- end }}

{{- if .Values.revisionHistoryLimit }}
  revisionHistoryLimit: {{ .Values.revisionHistoryLimit }}
{{- end }}

  strategy:
{{ toYaml .Values.strategy | nindent 4 }}

  selector:
    matchLabels:
      app: {{ .Values.app.name }}
      component: {{ .Values.app.component }}
      release: {{ .Release.Name }}

  template:
    metadata:
      labels:
        app: {{ .Values.app.name }}
        component: {{ .Values.app.component }}
        release: {{ .Release.Name }}
{{- with .Values.labels }}
{{ toYaml . | nindent 8 }}
{{- end }}

    spec:
      serviceAccountName: {{ include "vault.serviceAccountName" . }}

      restartPolicy: Always
      terminationGracePeriodSeconds: 10

      securityContext:
{{ include "securitycontext" . | nindent 8 }}

{{- if .Values.vault.enabled }}
      initContainers:
{{ include "vault_agent_init_container" . | nindent 8 }}
{{- end }}

      containers:
        - name: {{ .Values.app.containerName }}
          image: {{ template "app_image" . }}
          imagePullPolicy: {{ .Values.images.app.pullPolicy }}

{{ include "maverickSecurityContext" . | nindent 10 }}

{{- if .Values.command }}
          command:
{{ tpl (toYaml .Values.command) . | nindent 12 }}
{{- end }}

          ports:
            - name: {{ .Values.app.portName }}
              containerPort: {{ .Values.ports.http }}

          env:
{{ include "app_environment" . | nindent 12 }}

{{- if .Values.extraEnvFrom }}
          envFrom:
{{ toYaml .Values.extraEnvFrom | nindent 12 }}
{{- end }}

          resources:
{{ toYaml .Values.resources | nindent 12 }}

          volumeMounts:
{{ include "common_volume_mount" . | nindent 12 }}

{{- if .Values.csi.enabled }}
            - name: csi-secrets
              mountPath: {{ .Values.csi.csiMount }}
              readOnly: true
{{- end }}

{{- if .Values.livenessProbe }}
          livenessProbe:
            httpGet:
              path: /
              port: {{ .Values.livenessProbe.port }}
              scheme: {{ .Values.livenessProbe.scheme }}
            initialDelaySeconds: {{ .Values.livenessProbe.initialDelaySeconds }}
            timeoutSeconds: {{ .Values.livenessProbe.timeoutSeconds }}
            failureThreshold: {{ .Values.livenessProbe.failureThreshold }}
            periodSeconds: {{ .Values.livenessProbe.periodSeconds }}
{{- end }}

      volumes:
{{ include "consolidated_volumes" . | nindent 8 }}
```

---

# ✅ templates/hpa.yaml

```yaml
{{- if .Values.autoscaling.enabled }}
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: {{ .Release.Name }}
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: {{ .Release.Name }}

  minReplicas: {{ .Values.autoscaling.minReplicas }}
  maxReplicas: {{ .Values.autoscaling.maxReplicas }}

  metrics:
{{- if .Values.autoscaling.averageRelativeCPU }}
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: {{ .Values.autoscaling.averageRelativeCPU }}
{{- end }}

{{- if .Values.autoscaling.averageRelativeMemory }}
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: {{ .Values.autoscaling.averageRelativeMemory }}
{{- end }}
{{- end }}
```

---

# ✅ templates/service.yaml

```yaml
apiVersion: v1
kind: Service
metadata:
  name: {{ .Release.Name }}
  labels:
    app: {{ .Values.app.name }}
    component: {{ .Values.app.component }}
{{- with .Values.service.annotations }}
  annotations:
{{ toYaml . | nindent 4 }}
{{- end }}

spec:
  type: {{ .Values.service.type }}
  selector:
    app: {{ .Values.app.name }}
    component: {{ .Values.app.component }}
    release: {{ .Release.Name }}

  ports:
{{- range .Values.service.ports }}
    - name: {{ .name }}
      port: {{ .port }}
{{- end }}
```

---

# ✅ templates/ingress.yaml

```yaml
{{- if .Values.web.enabled }}
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: {{ .Release.Name }}-ingress
  namespace: {{ .Values.namespace }}
{{- with .Values.web.annotations }}
  annotations:
{{ toYaml . | nindent 4 }}
{{- end }}

spec:
  ingressClassName: {{ .Values.web.ingressClassName }}

{{- if .Values.web.tls.enabled }}
  tls:
    - secretName: {{ .Values.web.tls.secretName }}
{{- end }}

  rules:
{{- range .Values.web.hosts }}
    - host: {{ .name | quote }}
      http:
        paths:
          - path: {{ $.Values.web.path }}
            pathType: {{ $.Values.web.pathType }}
            backend:
              service:
                name: {{ $.Release.Name }}
                port:
                  name: {{ $.Values.app.portName }}
{{- end }}
{{- end }}
```

---

# ✅ templates/csi-config.yaml

```yaml
{{- if .Values.csi.enabled }}
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ .Release.Name }}-csi-config
data:
  csi_secrets.sh: |-
    #!/bin/bash
    set -euo pipefail

    MOUNT_PATH="{{ .Values.csi.csiMount }}"

{{- range $env, $file := .Values.csi.secrets.generic }}
    if [ -f "${MOUNT_PATH}/{{ $file }}" ]; then
      export {{ $env }}="$(cat ${MOUNT_PATH}/{{ $file }})"
    fi
{{- end }}
{{- end }}
```

---

# ✅ templates/csi-secret-provider.yaml

```yaml
{{- if .Values.csi.enabled }}
apiVersion: secrets-store.csi.x-k8s.io/v1
kind: SecretProviderClass
metadata:
  name: {{ .Release.Name }}-csi-secret-provider
spec:
  provider: azure
  parameters:
    useVMManagedIdentity: "true"
    tenantId: {{ .Values.csi.parameters.tenantId | quote }}
    userAssignedIdentityID: {{ .Values.csi.parameters.userAssignedIdentityID | quote }}
    keyvaultName: {{ .Values.csi.parameters.keyvaultName | quote }}
    objects: |
{{ .Values.csi.parameters.objects | nindent 6 }}
{{- end }}
```

---

# ✅ templates/vault-agent-configmap.yaml

(unchanged in behavior – only labels generic)

```yaml
{{- if .Values.vault.enabled }}
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ .Release.Name }}-vault-agent-config
data:
  vault-agent-config.hcl: |-
    exit_after_auth = true

    auto_auth {
      method "azure" {
        mount_path = "auth/azure"
        config = {
          role     = "{{ .Values.vault.role }}"
          resource = "{{ .Values.vault.resource }}"
        }
      }

      sink "file" {
        config = {
          path = "/home/vault/.vault-token"
        }
      }
    }

    template {
      destination = "/etc/secrets/secrets_eva.sh"
      perms       = "0755"

      contents = <<EOH
      #!/bin/sh
      set -e

      {{- $secretPath := "secret/runtime" }}

      {{- range $key, $path := .Values.vault.secrets.generic }}
      export {{ $key }}='{{ with secret (printf "%s/%s" $secretPath $path) }}{{ .Data.data.password }}{{ end }}'
      {{- end }}

      {{- range $key, $path := .Values.vault.secrets.postgres }}
      export {{ $key }}='postgresql://{{ $.Values.data.metadataConnection.user }}:{{ with secret (printf "%s/%s" $secretPath $path) }}{{ .Data.data.password | urlquery }}{{ end }}@{{ $.Values.data.metadataConnection.host }}:{{ $.Values.data.metadataConnection.port }}/{{ $.Values.data.metadataConnection.db }}?sslmode={{ $.Values.data.metadataConnection.sslmode }}'
      {{- end }}

      EOH
{{- end }}
```

---

# ✅ templates/_helpers.tpl

```yaml
{{- define "app.fullname" -}}
{{- printf "%s" .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- end }}

{{- define "app_image" -}}
{{- $image := .Values.images.app -}}
{{- if $image.digest -}}
{{ printf "%s@%s" $image.repository $image.digest }}
{{- else -}}
{{ printf "%s:%s" $image.repository $image.tag }}
{{- end -}}
{{- end }}

{{- define "vault.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{ default (include "app.fullname" .) .Values.serviceAccount.name }}
{{- else if .Values.serviceAccount.name -}}
{{ .Values.serviceAccount.name }}
{{- end -}}
{{- end }}

{{- define "securitycontext" -}}
{{ toYaml .Values.securityContext }}
{{- end }}

{{- define "maverickSecurityContext" -}}
securityContext:
{{ toYaml .Values.maverickSecurityContext | nindent 2 }}
{{- end }}

{{- define "vault_agent_init_container" -}}
- name: vault-agent-init
  image: {{ .Values.vault.image }}
  args:
    - agent
    - -config=/vault/config/vault-agent-config.hcl
  volumeMounts:
    - name: vault-config
      mountPath: /vault/config
    - name: vault-secrets
      mountPath: /etc/secrets
{{- end }}

{{- define "app_environment" -}}
{{- range .Values.globalEnv }}
- name: {{ .name }}
  value: {{ .value | quote }}
{{- end }}
{{- range .Values.env }}
- name: {{ .name }}
  value: {{ .value | quote }}
{{- end }}
{{- end }}

{{- define "common_volume_mount" -}}
{{- range .Values.volumeMounts }}
- name: {{ .name }}
  mountPath: {{ .mountPath }}
{{- end }}
{{- end }}

{{- define "consolidated_volumes" -}}
{{- range .Values.volumes }}
- name: {{ .name }}
  emptyDir: {}
{{- end }}

{{- if .Values.vault.enabled }}
- name: vault-config
  configMap:
    name: {{ .Release.Name }}-vault-agent-config
{{- end }}

{{- if .Values.csi.enabled }}
- name: csi-secrets
  csi:
    driver: secrets-store.csi.k8s.io
    readOnly: true
    volumeAttributes:
      secretProviderClass: {{ .Release.Name }}-csi-secret-provider
{{- end }}
{{- end }}

{{- define "appstartCommands" -}}
{{- range .Values.unconditionalCommandList }}
{{ . }}
{{- end }}
{{- end }}

{{- define "javaProperties" -}}
{{- range .Values.javaPropertiesList }}
{{ . }}
{{- end }}
{{- end }}
```

---

## ✅ What you now have

You can reuse this chart for **any Java service** by changing only:

```yaml
app:
  name:
  jarName:
images.app.repository
ports.http
web.path
```

Everything else (Vault, CSI, HPA, WI, ingress, probes, volumes) remains shared and consistent across your platform.

This is now a proper internal **generic java-service Helm chart** suitable for your observability and Airflow-adjacent services.
