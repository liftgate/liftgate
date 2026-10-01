export type User = {
  id: string;
  login: string;
  name: string | null;
  email: string | null;
  avatarUrl: string | null;
  status: UserStatus;
  operator?: boolean;
  termsPending?: boolean;
};

export type Organization = {
  id: string;
  slug: string;
  name: string;
  plan: string;
  suspendedAt: string | null;
  suspendedReason: string | null;
  role: OrgRole | null;
};

export type Project = {
  id: string;
  orgId: string;
  slug: string;
  name: string;
  repoFullName: string;
  repoDefaultBranch: string;
  installationId: number;
  previewsEnabled: boolean;
  previewBaseEnvironmentId: string | null;
};

export type EnvironmentKind = "production" | "preview";

export type Environment = {
  id: string;
  projectId: string;
  slug: string;
  name: string;
  kind: EnvironmentKind;
  branch: string;
  namespace: string;
  pullRequest: number | null;
};

export type ServiceKind = "web" | "worker" | "cron" | "static";
export type BuildStrategy = "auto" | "dockerfile";

export type ServiceSpec = {
  slug: string;
  name: string;
  kind: ServiceKind;
  rootDir: string;
  buildStrategy: BuildStrategy;
  dockerfilePath: string;
  port: number | null;
  replicas: number;
  cpuMillis: number;
  memoryMb: number;
  cronSchedule: string | null;
  startCommand: string | null;
  healthCheckPath: string | null;
  watchPaths: string[];
  volume: Volume | null;
  buildCommand?: string | null;
  framework?: string | null;
};

export type Service = ServiceSpec & { id: string; environmentId: string; internalHost: string | null; url: string | null; current: CurrentDeployment | null };

export type EnvVar = { name: string; value: string | null; secret: boolean };

export type BuildStatus = "queued" | "running" | "succeeded" | "failed" | "cancelled";

export type Build = {
  id: string;
  serviceId: string;
  commitSha: string;
  commitMessage: string | null;
  branch: string;
  status: BuildStatus;
  imageRef: string | null;
  error: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  createdAt: string;
  imagePruned: boolean;
};

export type DeploymentStatus = "pending" | "releasing" | "running" | "failed" | "superseded" | "rolled_back";

export type DeploymentHealth = "healthy" | "degraded" | "down";

export type Deployment = {
  id: string;
  serviceId: string;
  buildId: string;
  status: DeploymentStatus;
  replicasReady: number;
  error: string | null;
  createdAt: string;
  health: DeploymentHealth | null;
};

export type DomainKind = "platform" | "custom";

export type Domain = {
  id: string;
  serviceId: string;
  hostname: string;
  kind: DomainKind;
  verificationToken: string | null;
  verifiedAt: string | null;
  certificateStatus: string;
  certificateMessage: string | null;
  dnsRecords: DnsRecord[];
};

export type OAuthProvider = "github" | "google" | "gitlab" | "bitbucket";

export type IdentityProvider = OAuthProvider | "email" | "saml";

export type AuthProviders = {
  oauth: OAuthProvider[];
  passkey: boolean;
  email: boolean;
  sso: boolean;
  customDomains: boolean;
  storage: boolean;
  deployDomain: string;
  termsUrl?: string;
  privacyUrl?: string;
  aupUrl?: string;
};

export type Identity = { id: string; provider: IdentityProvider; email: string | null; createdAt: string; lastUsedAt: string | null };

export type Passkey = { id: string; name: string; createdAt: string; lastUsedAt: string | null };

export type GitConnection = { provider: "github"; accountLogin: string; connectedAt: string };

export type OrgRole = "owner" | "admin" | "member";

export type SsoConnection = {
  idpEntityId: string;
  idpSsoUrl: string;
  idpCertificate: string;
  emailDomains: string[];
  defaultRole: OrgRole;
  verifiedDomains: string[];
  verificationToken: string;
};

export type SsoServiceProvider = { entityId: string; acsUrl: string };

export type UserStatus = "pending" | "active" | "suspended";

export type ApiToken = {
  id: string;
  name: string;
  createdBy: User;
  createdAt: string;
  lastUsedAt: string | null;
  expiresAt: string | null;
};

export type ProjectTree = { project: Project; environments: Environment[]; services: Service[] };

export type Plan = {
  ownedOrgs: number | null;
  projects: number | null;
  environmentsPerProject: number | null;
  services: number | null;
  cpuMillis: number | null;
  memoryMb: number | null;
  replicas: number | null;
  cpuRequestRatio: number;
  ephemeralMb: number;
  customDomains: number | null;
  concurrentBuilds: number | null;
  buildsPerHour: number | null;
  egressBandwidth: string | null;
  udp: boolean;
  storageGb: number | null;
};

export type Usage = {
  plan: string;
  limits: Plan;
  projects: number;
  services: number;
  customDomains: number;
  replicas: number;
  cpuMillis: number;
  memoryMb: number;
  storageGb: number;
};

export type NotificationKind = "slack" | "discord" | "webhook";

export type NotificationEvent = "build_failed" | "deployment_running" | "deployment_failed";

export type NotificationChannel = {
  id: string;
  name: string;
  kind: NotificationKind;
  host: string;
  events: NotificationEvent[];
  createdAt: string;
  secret: string | null;
};

export type Member = { user: User; role: OrgRole };

export type CreatedInvitation = { url: string; expiresAt: string; emailed: boolean };

export type InvitationPreview = { slug: string; name: string; role: OrgRole; invitedBy: string };

export type AuditEntry = {
  id: number;
  actor: User | null;
  viaToken: boolean;
  action: string;
  targetType: string;
  targetId: string;
  details: Record<string, string>;
  createdAt: string;
};

export type CurrentDeployment = { deploymentId: string; status: DeploymentStatus; replicasReady: number; commitSha: string; createdAt: string };

export type GitHubRepository = { fullName: string; defaultBranch: string; private: boolean };

export type ImportableRepositories = { repositories: GitHubRepository[]; installUrl: string };

export type DnsRecord = { type: "TXT" | "CNAME"; name: string; value: string };

export type MetricPoint = { time: number; value: number };

export type ServiceMetrics = {
  start: number;
  end: number;
  step: number;
  cpu: MetricPoint[];
  memory: MetricPoint[];
  memoryLimitBytes: number;
  networkRx: MetricPoint[];
  networkTx: MetricPoint[];
  restarts: number;
};

export type PullRequest = {
  number: number;
  title: string;
  headRef: string;
  headSha: string;
  fork: boolean;
  approvedSha: string | null;
  error: string | null;
  updatedAt: string;
};

export type PreviewStatus = { missing: string[] | null; pullRequests: PullRequest[] };

export type Volume = { mountPath: string; sizeGb: number };

export type ServiceLink = { serviceId: string; envName: string };

export type Database = {
  id: string;
  environmentId: string;
  slug: string;
  storageGb: number;
  cpuMillis: number;
  memoryMb: number;
  restoredFrom: string | null;
  restoreTarget: string | null;
  createdAt: string;
  links: ServiceLink[];
  ready: boolean;
};

export type Backup = { name: string; phase: string | null; startedAt: string | null; stoppedAt: string | null };

export type OperatorUser = { user: User; providers: IdentityProvider[]; orgs: number; createdAt: string };

export type OperatorOrg = { org: Organization; members: number; projects: number; services: number; createdAt: string };

export type OperatorSummary = { pending: number; plans: string[] };

export type DetectedVariable = { name: string; description: string | null; source: string; required: boolean; secretHint: boolean };

export type DetectedService = {
  selected: boolean;
  framework: { id: string; name: string } | null;
  packageManager: string | null;
  builder: "railpack" | "dockerfile";
  spec: ServiceSpec;
  defaults: { build: string | null; start: string | null };
  evidence: string;
  warnings: string[];
  healthHint: string | null;
  variables: DetectedVariable[];
};

export type Detection = {
  ref: string;
  commit: { sha: string; message: string | null } | null;
  partial: boolean;
  services: DetectedService[];
  directories: { path: string; framework: string | null }[];
  warnings: string[];
};
