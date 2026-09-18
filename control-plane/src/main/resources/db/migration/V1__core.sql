create table users (
    id uuid primary key,
    github_id bigint not null unique,
    login text not null,
    name text,
    email text,
    avatar_url text,
    created_at timestamptz not null default now()
);

create table organizations (
    id uuid primary key,
    slug text not null unique,
    name text not null,
    plan text not null default 'free',
    created_at timestamptz not null default now()
);

create table memberships (
    org_id uuid not null references organizations (id) on delete cascade,
    user_id uuid not null references users (id) on delete cascade,
    role text not null check (role in ('owner', 'admin', 'member')),
    created_at timestamptz not null default now(),
    primary key (org_id, user_id)
);

create table sessions (
    id text primary key,
    user_id uuid not null references users (id) on delete cascade,
    github_token bytea not null,
    expires_at timestamptz not null,
    created_at timestamptz not null default now()
);

create table api_tokens (
    id uuid primary key,
    org_id uuid not null references organizations (id) on delete cascade,
    name text not null,
    token_hash text not null unique,
    created_by uuid not null references users (id),
    created_at timestamptz not null default now(),
    last_used_at timestamptz
);

create table github_installations (
    id bigint primary key,
    org_id uuid not null references organizations (id) on delete cascade,
    account_login text not null,
    created_at timestamptz not null default now()
);

create table projects (
    id uuid primary key,
    org_id uuid not null references organizations (id) on delete cascade,
    slug text not null,
    name text not null,
    repo_full_name text not null,
    repo_default_branch text not null default 'main',
    installation_id bigint not null references github_installations (id),
    created_at timestamptz not null default now(),
    unique (org_id, slug)
);

create table environments (
    id uuid primary key,
    project_id uuid not null references projects (id) on delete cascade,
    slug text not null,
    name text not null,
    kind text not null check (kind in ('production', 'preview')),
    branch text not null,
    namespace text not null unique,
    created_at timestamptz not null default now(),
    unique (project_id, slug)
);

create table services (
    id uuid primary key,
    environment_id uuid not null references environments (id) on delete cascade,
    slug text not null,
    name text not null,
    kind text not null check (kind in ('web', 'worker', 'cron', 'static')),
    root_dir text not null default '/',
    build_strategy text not null default 'auto' check (build_strategy in ('auto', 'dockerfile')),
    dockerfile_path text not null default 'Dockerfile',
    port integer,
    replicas integer not null default 1,
    cpu_millis integer not null default 500,
    memory_mb integer not null default 512,
    cron_schedule text,
    start_command text,
    created_at timestamptz not null default now(),
    unique (environment_id, slug)
);

create table env_vars (
    id uuid primary key,
    service_id uuid not null references services (id) on delete cascade,
    name text not null,
    value_encrypted bytea not null,
    is_secret boolean not null default false,
    created_at timestamptz not null default now(),
    unique (service_id, name)
);

create table builds (
    id uuid primary key,
    service_id uuid not null references services (id) on delete cascade,
    commit_sha text not null,
    commit_message text,
    branch text not null,
    status text not null check (status in ('queued', 'running', 'succeeded', 'failed', 'cancelled')),
    image_ref text,
    error text,
    started_at timestamptz,
    finished_at timestamptz,
    created_at timestamptz not null default now()
);

create index builds_service_created on builds (service_id, created_at desc);

create table deployments (
    id uuid primary key,
    service_id uuid not null references services (id) on delete cascade,
    build_id uuid not null references builds (id),
    status text not null check (status in ('pending', 'releasing', 'running', 'failed', 'superseded', 'rolled_back')),
    replicas_ready integer not null default 0,
    error text,
    started_at timestamptz,
    finished_at timestamptz,
    created_at timestamptz not null default now()
);

create index deployments_service_created on deployments (service_id, created_at desc);

create table domains (
    id uuid primary key,
    service_id uuid not null references services (id) on delete cascade,
    hostname text not null,
    kind text not null check (kind in ('platform', 'custom')),
    verification_token text,
    verified_at timestamptz,
    certificate_status text not null default 'pending',
    created_at timestamptz not null default now(),
    unique (service_id, hostname)
);

create unique index domains_verified_hostname on domains (hostname) where verified_at is not null;

create table usage_records (
    id bigserial primary key,
    org_id uuid not null references organizations (id) on delete cascade,
    service_id uuid references services (id) on delete set null,
    metric text not null,
    quantity numeric(20, 6) not null,
    window_start timestamptz not null,
    window_end timestamptz not null
);

create index usage_records_org_window on usage_records (org_id, window_start);

create table audit_log (
    id bigserial primary key,
    org_id uuid references organizations (id) on delete cascade,
    actor_user_id uuid references users (id) on delete set null,
    action text not null,
    target_type text not null,
    target_id text not null,
    details jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table outbox (
    id bigserial primary key,
    subject text not null,
    payload jsonb not null,
    created_at timestamptz not null default now(),
    published_at timestamptz
);

create index outbox_unpublished on outbox (id) where published_at is null;
