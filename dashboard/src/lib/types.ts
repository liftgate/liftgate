export type User = {
  id: string;
  login: string;
  name: string | null;
  email: string | null;
  avatarUrl: string | null;
};

export type Organization = { id: string; slug: string; name: string; plan: string };

export type Project = {
  id: string;
  orgId: string;
  slug: string;
  name: string;
  repoFullName: string;
  repoDefaultBranch: string;
  installationId: number;
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
};

export type Service = ServiceSpec & { id: string; environmentId: string };

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
};

export type DeploymentStatus = "pending" | "releasing" | "running" | "failed" | "superseded" | "rolled_back";

export type Deployment = {
  id: string;
  serviceId: string;
  buildId: string;
  status: DeploymentStatus;
  replicasReady: number;
  error: string | null;
  createdAt: string;
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
};

export type OAuthProvider = "github" | "google" | "gitlab" | "bitbucket";

export type IdentityProvider = OAuthProvider | "email" | "saml";

export type AuthProviders = { oauth: OAuthProvider[]; passkey: boolean; email: boolean; sso: boolean };

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
