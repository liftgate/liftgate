"use client";

import { useState } from "react";
import { api } from "@/lib/api";
import { useAction, useApi } from "@/lib/hooks";
import type { NotificationChannel, NotificationEvent, NotificationKind } from "@/lib/types";
import { formValues, timeAgo } from "@/lib/util";
import { Loaded } from "@/components/loaded";
import { PageHeader } from "@/components/page-header";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { CopyField } from "@/components/ui/copy-field";
import { ConfirmDialog, Dialog } from "@/components/ui/dialog";
import { EmptyState } from "@/components/ui/empty-state";
import { Field, FormError, Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { TableSkeleton } from "@/components/ui/skeleton";
import { Cell, Row, Table } from "@/components/ui/table";

const kinds: { value: NotificationKind; label: string; hint: string }[] = [
  { value: "slack", label: "Slack", hint: "An incoming webhook URL from your Slack app" },
  { value: "discord", label: "Discord", hint: "A webhook URL from the channel's Integrations settings" },
  { value: "webhook", label: "Webhook", hint: "Your HTTPS endpoint receives signed JSON" },
];

const events: { value: NotificationEvent; label: string }[] = [
  { value: "deployment_running", label: "Deployment live" },
  { value: "deployment_failed", label: "Deployment failed" },
  { value: "build_failed", label: "Build failed" },
];

const labelOf = (options: { value: string; label: string }[], value: string) => options.find((o) => o.value === value)?.label ?? value;

const channelBody = (form: HTMLFormElement) => ({ name: formValues(form).name, events: new FormData(form).getAll("events") });

function ChannelForm({
  channel,
  pending,
  error,
  onSubmit,
  onCancel,
}: {
  channel?: NotificationChannel;
  pending: boolean;
  error?: string;
  onSubmit: (form: HTMLFormElement) => void;
  onCancel: () => void;
}) {
  const [kind, setKind] = useState<NotificationKind>("slack");
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        onSubmit(e.currentTarget);
      }}
      className="flex flex-col gap-4"
    >
      <Field label="Name" hint="Where the messages land, for example #deploys">
        <Input name="name" required maxLength={100} defaultValue={channel?.name} />
      </Field>
      {!channel && (
        <>
          <Field label="Destination">
            <Select name="kind" value={kind} onChange={(e) => setKind(e.target.value as NotificationKind)}>
              {kinds.map((k) => (
                <option key={k.value} value={k.value}>
                  {k.label}
                </option>
              ))}
            </Select>
          </Field>
          <Field label="URL" hint={kinds.find((k) => k.value === kind)?.hint}>
            <Input name="url" type="url" required maxLength={2048} pattern="https://.+" placeholder="https://" className="font-mono" />
          </Field>
        </>
      )}
      <fieldset className="text-sm">
        <legend className="font-medium text-graphite-200">Events</legend>
        <div className="mt-2 flex flex-col gap-2">
          {events.map((event) => (
            <label key={event.value} className="flex items-center gap-2 text-graphite-200">
              <input
                type="checkbox"
                name="events"
                value={event.value}
                defaultChecked={channel ? channel.events.includes(event.value) : true}
                className="accent-accent"
              />
              {event.label}
            </label>
          ))}
        </div>
      </fieldset>
      <FormError message={error} />
      <div className="flex justify-end gap-2">
        <Button onClick={onCancel}>Cancel</Button>
        <Button type="submit" variant="primary" pending={pending}>
          {channel ? "Save changes" : "Add channel"}
        </Button>
      </div>
    </form>
  );
}

export function Notifications({ org }: { org: string }) {
  const path = `/orgs/${org}/notifications`;
  const channels = useApi<NotificationChannel[]>(path);
  const [creating, setCreating] = useState(false);
  const [secret, setSecret] = useState<string>();
  const [editing, setEditing] = useState<NotificationChannel>();
  const [removing, setRemoving] = useState<NotificationChannel>();
  const [testing, setTesting] = useState<NotificationChannel>();
  const [tested, setTested] = useState<string>();
  const create = useAction(async (form: HTMLFormElement) => {
    const v = formValues(form);
    const created = await api<NotificationChannel>(path, { method: "POST", body: { ...channelBody(form), kind: v.kind, url: v.url } });
    channels.reload();
    if (created.secret) setSecret(created.secret);
    else setCreating(false);
  });
  const update = useAction(async (channel: NotificationChannel, form: HTMLFormElement) => {
    await api(`${path}/${channel.id}`, { method: "PATCH", body: channelBody(form) });
    setEditing(undefined);
    channels.reload();
  });
  const remove = useAction(async (channel: NotificationChannel) => {
    await api(`${path}/${channel.id}`, { method: "DELETE" });
    setRemoving(undefined);
    channels.reload();
  });
  const test = useAction(async (channel: NotificationChannel) => {
    setTesting(channel);
    setTested(undefined);
    await api(`${path}/${channel.id}/test`, { method: "POST" });
    setTested(channel.name);
  });
  const close = () => {
    setCreating(false);
    setSecret(undefined);
  };
  const newChannel = (
    <Button variant="primary" onClick={() => setCreating(true)}>
      New channel
    </Button>
  );
  if (channels.error?.status === 403)
    return <EmptyState title="Only admins can manage notifications" description="Ask an owner or admin of this organization to add a channel." />;
  return (
    <div className="flex flex-col gap-8">
      <PageHeader
        title="Notifications"
        description="Post build failures and deployments of every project in this organization to Slack, Discord or your own endpoint."
        actions={!!channels.data?.length && newChannel}
      />
      <Loaded query={channels} skeleton={<TableSkeleton />}>
        {(list) =>
          list.length === 0 ? (
            <EmptyState title="No notification channels" description="Add one to hear when a deployment goes live or fails." action={newChannel} />
          ) : (
            <div className="flex flex-col gap-4">
              <Table columns={["Name", "Destination", "Events", "Created", ""]}>
                {list.map((channel) => (
                  <Row key={channel.id}>
                    <Cell className="font-medium">{channel.name}</Cell>
                    <Cell className="text-graphite-200">
                      {labelOf(kinds, channel.kind)} <span className="font-mono text-xs text-graphite-400">{channel.host}</span>
                    </Cell>
                    <Cell>
                      <div className="flex flex-wrap gap-2">
                        {channel.events.map((event) => (
                          <Badge key={event}>{labelOf(events, event)}</Badge>
                        ))}
                      </div>
                    </Cell>
                    <Cell className="text-graphite-400">{timeAgo(channel.createdAt)}</Cell>
                    <Cell>
                      <div className="flex justify-end gap-2">
                        <Button pending={test.pending && testing?.id === channel.id} onClick={() => test.run(channel)}>
                          Send test
                        </Button>
                        <Button variant="ghost" onClick={() => setEditing(channel)}>
                          Edit
                        </Button>
                        <Button variant="danger" onClick={() => setRemoving(channel)}>
                          Delete
                        </Button>
                      </div>
                    </Cell>
                  </Row>
                ))}
              </Table>
              <div aria-live="polite">
                {test.error ? (
                  <FormError message={`${testing?.name}: ${test.error}`} />
                ) : (
                  tested && <p className="text-sm text-graphite-400">Sent a test message to {tested}.</p>
                )}
              </div>
            </div>
          )
        }
      </Loaded>
      <Dialog open={creating} title={secret ? "Copy the signing secret" : "New notification channel"} onClose={close}>
        {secret ? (
          <div className="flex flex-col gap-4">
            <p className="text-sm text-graphite-200">
              Each request carries <code className="font-mono text-xs">X-Liftgate-Signature: sha256=</code> followed by the hex HMAC-SHA256 of the body under this
              secret. It is shown only now.
            </p>
            <CopyField label="Signing secret" value={secret} />
            <div className="flex justify-end">
              <Button variant="primary" onClick={close}>
                Done
              </Button>
            </div>
          </div>
        ) : (
          <ChannelForm pending={create.pending} error={create.error} onSubmit={create.run} onCancel={close} />
        )}
      </Dialog>
      <Dialog open={!!editing} title="Edit channel" onClose={() => setEditing(undefined)}>
        {editing && (
          <ChannelForm
            channel={editing}
            pending={update.pending}
            error={update.error}
            onSubmit={(form) => update.run(editing, form)}
            onCancel={() => setEditing(undefined)}
          />
        )}
      </Dialog>
      <ConfirmDialog
        open={!!removing}
        title="Delete channel"
        pending={remove.pending}
        error={remove.error}
        onConfirm={() => removing && remove.run(removing)}
        onClose={() => setRemoving(undefined)}
      >
        <span className="font-medium text-white">{removing?.name}</span> stops receiving notifications, including any still being retried.
      </ConfirmDialog>
    </div>
  );
}
