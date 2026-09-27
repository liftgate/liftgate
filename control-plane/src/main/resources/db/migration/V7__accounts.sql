alter table users
    add column status text not null default 'active' check (status in ('pending', 'active', 'suspended')),
    add column terms_accepted_at timestamptz;

alter table organizations
    add column suspended_at timestamptz,
    add column suspended_reason text;
