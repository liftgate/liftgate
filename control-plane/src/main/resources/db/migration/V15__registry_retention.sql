alter table builds add column image_pruned boolean not null default false;

create table registry_orphans (
    repository text primary key
);
