create table notification_channels (
    id uuid primary key,
    org_id uuid not null references organizations (id) on delete cascade,
    name text not null,
    kind text not null check (kind in ('webhook', 'slack', 'discord')),
    url_encrypted bytea not null,
    secret_encrypted bytea,
    events text[] not null,
    created_at timestamptz not null default now()
);

create index notification_channels_org on notification_channels (org_id);
