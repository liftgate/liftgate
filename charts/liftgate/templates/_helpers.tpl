{{- define "liftgate.fullname" -}}
{{- if contains .Chart.Name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{- define "liftgate.labels" -}}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "liftgate.selectorLabels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "liftgate.ha" -}}
{{- eq .Values.profile "ha" -}}
{{- end -}}

{{- define "liftgate.host" -}}
{{- (urlParse .).hostname -}}
{{- end -}}

{{- define "liftgate.dashboardUrl" -}}
{{- .Values.dashboardUrl | default .Values.publicUrl -}}
{{- end -}}

{{- define "liftgate.hosts" -}}
{{- uniq (list (include "liftgate.host" .Values.publicUrl) (include "liftgate.host" (include "liftgate.dashboardUrl" .))) | toJson -}}
{{- end -}}

{{- define "liftgate.gatewayNamespace" -}}
{{- and (not .Values.gateway.create) .Values.gateway.namespace | default .Release.Namespace -}}
{{- end -}}

{{- define "liftgate.postgresCluster" -}}
{{- include "liftgate.fullname" . }}-postgres
{{- end -}}

{{- define "liftgate.databaseUrl" -}}
{{- if .Values.postgres.managed -}}
jdbc:postgresql://{{ include "liftgate.postgresCluster" . }}-rw.{{ .Release.Namespace }}.svc:5432/liftgate
{{- else -}}
{{- .Values.postgres.externalUrl -}}
{{- end -}}
{{- end -}}

{{- define "liftgate.natsService" -}}
{{- if .Values.nats.fullnameOverride -}}
{{- .Values.nats.fullnameOverride -}}
{{- else if contains "nats" .Release.Name -}}
{{- .Release.Name -}}
{{- else -}}
{{- .Release.Name }}-nats
{{- end -}}
{{- end -}}

{{- define "liftgate.natsUrl" -}}
{{- if .Values.nats.managed -}}
nats://{{ include "liftgate.natsService" . }}.{{ .Release.Namespace }}.svc:4222
{{- else -}}
{{- .Values.nats.externalUrl -}}
{{- end -}}
{{- end -}}
