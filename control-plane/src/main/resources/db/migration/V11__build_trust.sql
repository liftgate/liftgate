alter table projects add column imported_by_login text;

alter table builds add column registry_secret_hash text;

alter table github_installations
    alter column org_id drop not null,
    drop constraint github_installations_org_id_fkey,
    add foreign key (org_id) references organizations (id) on delete set null;
