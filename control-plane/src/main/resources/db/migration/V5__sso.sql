create table sso_connections (
    id uuid primary key,
    org_id uuid not null unique references organizations (id) on delete cascade,
    idp_entity_id text not null,
    idp_sso_url text not null,
    idp_certificate text not null,
    email_domains text[] not null,
    verified_domains text[] not null default '{}',
    verification_token text not null,
    default_role text not null default 'member' check (default_role in ('admin', 'member')),
    created_at timestamptz not null default now()
);
