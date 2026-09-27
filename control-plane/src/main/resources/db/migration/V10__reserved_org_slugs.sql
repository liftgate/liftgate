do $$
declare
    taken text := (
        select string_agg(slug, ', ' order by slug) from organizations
        where slug in ('docs', 'new', 'settings', 'admin', 'status', 'pricing', 'blog', 'changelog', 'help', 'support',
                       'legal', 'terms', 'privacy', 'security', 'sso', 'signup', 'logout', 'www', 'app', 'dashboard')
    );
begin
    if taken is not null then
        raise exception 'organization slugs % are now reserved; rename these organizations before upgrading', taken;
    end if;
end $$;
