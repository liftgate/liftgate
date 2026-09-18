create table passkeys (
    id uuid primary key,
    user_id uuid not null references users (id) on delete cascade,
    credential_id bytea not null unique,
    public_key bytea not null,
    signature_count bigint not null,
    name text not null,
    created_at timestamptz not null default now(),
    last_used_at timestamptz
);
create index passkeys_user on passkeys (user_id);
