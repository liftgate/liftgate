alter table services add column volume jsonb;

create table databases (
    id uuid primary key,
    environment_id uuid not null references environments (id) on delete cascade,
    slug text not null,
    storage_gb integer not null,
    cpu_millis integer not null,
    memory_mb integer not null,
    restored_from uuid,
    restore_target timestamptz,
    created_at timestamptz not null default now(),
    unique (environment_id, slug)
);

create table service_links (
    service_id uuid not null references services (id) on delete cascade,
    database_id uuid not null references databases (id),
    env_name text not null,
    primary key (service_id, env_name)
);

create index service_links_database on service_links (database_id);
