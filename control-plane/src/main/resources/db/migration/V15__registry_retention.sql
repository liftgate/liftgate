do $$
begin
    if exists (select from organizations where slug = 'liftgate') then
        raise exception 'the organization slug liftgate is now reserved; rename that organization before upgrading';
    end if;
end $$;

alter table builds add column image_pruned boolean not null default false;

alter table deployments add column reached_running boolean not null default false;

update deployments set reached_running = true where status in ('running', 'rolled_back');

create table registry_orphans (
    repository text primary key
);
