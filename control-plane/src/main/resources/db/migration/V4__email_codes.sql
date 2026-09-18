create table email_codes (
    email text primary key,
    code_hash bytea not null,
    attempts integer not null default 0,
    expires_at timestamptz not null
);
