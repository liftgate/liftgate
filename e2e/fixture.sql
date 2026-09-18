insert into users (id, github_id, login) values
    ('00000000-0000-4000-8000-000000000001', 1, 'e2e');

insert into organizations (id, slug, name) values
    ('00000000-0000-4000-8000-000000000002', 'e2e', 'End to end');

insert into memberships (org_id, user_id, role) values
    ('00000000-0000-4000-8000-000000000002', '00000000-0000-4000-8000-000000000001', 'owner');

insert into github_installations (id, org_id, account_login) values
    (1, '00000000-0000-4000-8000-000000000002', 'e2e');

insert into projects (id, org_id, slug, name, repo_full_name, installation_id) values
    ('00000000-0000-4000-8000-000000000003', '00000000-0000-4000-8000-000000000002', 'hello', 'Hello', 'e2e/hello', 1);

insert into environments (id, project_id, slug, name, kind, branch, namespace) values
    ('00000000-0000-4000-8000-000000000004', '00000000-0000-4000-8000-000000000003', 'production', 'Production', 'production', 'main', 'env-e2e');

insert into services (id, environment_id, slug, name, kind, port, cpu_millis, memory_mb, start_command) values
    ('00000000-0000-4000-8000-000000000005', '00000000-0000-4000-8000-000000000004', 'web', 'Web', 'web', 8080, 100, 64,
     'echo liftgate > /tmp/index.html && exec httpd -f -p 8080 -h /tmp');

insert into domains (id, service_id, hostname, kind, verified_at, certificate_status) values
    ('00000000-0000-4000-8000-000000000006', '00000000-0000-4000-8000-000000000005', 'web-hello-e2e.liftgate.app', 'platform', now(), 'issued');

insert into builds (id, service_id, commit_sha, branch, status, image_ref, started_at, finished_at) values
    ('00000000-0000-4000-8000-000000000007', '00000000-0000-4000-8000-000000000005', '0000000000000000000000000000000000000000', 'main', 'succeeded', 'busybox:1.36', now(), now());

insert into deployments (id, service_id, build_id, status) values
    ('00000000-0000-4000-8000-000000000008', '00000000-0000-4000-8000-000000000005', '00000000-0000-4000-8000-000000000007', 'pending');

insert into outbox (subject, payload) values
    ('liftgate.release.requested', '{"deploymentId": "00000000-0000-4000-8000-000000000008"}');
