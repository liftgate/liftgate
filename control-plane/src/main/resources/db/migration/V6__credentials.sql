alter table api_tokens add column expires_at timestamptz;
delete from sessions;
