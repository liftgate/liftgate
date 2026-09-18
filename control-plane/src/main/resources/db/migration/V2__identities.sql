create table identities (
    id uuid primary key,
    user_id uuid not null references users (id) on delete cascade,
    provider text not null check (provider in ('github', 'google', 'gitlab', 'bitbucket', 'email', 'saml')),
    subject text not null,
    email text,
    email_verified boolean not null default false,
    created_at timestamptz not null default now(),
    last_used_at timestamptz,
    unique (provider, subject)
);
insert into identities (id, user_id, provider, subject, email)
    select gen_random_uuid(), id, 'github', github_id::text, email from users;
alter table users drop column github_id;
alter table users add column email_verified boolean not null default false;
create unique index users_verified_email on users (lower(email)) where email_verified;

create table git_connections (
    user_id uuid not null references users (id) on delete cascade,
    provider text not null check (provider in ('github')),
    account_login text not null,
    access_token bytea not null,
    refresh_token bytea,
    expires_at timestamptz,
    created_at timestamptz not null default now(),
    primary key (user_id, provider)
);
alter table sessions drop column github_token;
