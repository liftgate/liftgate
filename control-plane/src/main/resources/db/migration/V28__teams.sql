create table invitations (
    id uuid primary key,
    org_id uuid not null references organizations (id) on delete cascade,
    role text not null check (role in ('owner', 'admin', 'member')),
    token_hash text not null unique,
    created_by uuid not null references users (id) on delete cascade,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null,
    accepted_at timestamptz
);

alter table audit_log add column via_token boolean not null default false;

create index audit_log_org on audit_log (org_id, id);
