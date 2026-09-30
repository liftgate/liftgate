alter table projects
    add column previews_enabled boolean not null default false,
    add column preview_base_environment_id uuid references environments (id) on delete set null;

alter table environments add column pull_request integer;

create unique index environments_pull_request on environments (project_id, pull_request);

create table pull_requests (
    project_id uuid not null references projects (id) on delete cascade,
    number integer not null,
    title text not null,
    head_ref text not null,
    head_sha text not null,
    fork boolean not null,
    approved_sha text,
    comment_id bigint,
    error text,
    updated_at timestamptz not null,
    primary key (project_id, number)
);
