alter table services add column health_check_path text;

alter table deployments add column health text check (health in ('healthy', 'degraded', 'down'));
