"use client";

import { useState } from "react";
import { api } from "@/lib/api";
import { useAction, useApi } from "@/lib/hooks";
import type { ApiToken } from "@/lib/types";
import { formValues, lastUsed, timeAgo } from "@/lib/util";
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

const lifetimes = [
  { days: "30", label: "30 days" },
  { days: "90", label: "90 days" },
  { days: "365", label: "1 year" },
  { days: "", label: "Never" },
];

const expired = (iso: string) => new Date(iso).getTime() <= Date.now();

function Expires({ at }: { at: string | null }) {
  if (at && expired(at)) return <Badge tone="danger">Expired</Badge>;
  return <span className="text-graphite-400">{at ? new Date(at).toLocaleDateString() : "Never"}</span>;
}

export function Tokens({ org }: { org: string }) {
  const path = `/orgs/${org}/tokens`;
  const tokens = useApi<ApiToken[]>(path);
  const [creating, setCreating] = useState(false);
  const [secret, setSecret] = useState<string>();
  const [revoking, setRevoking] = useState<ApiToken>();
  const create = useAction(async (form: HTMLFormElement) => {
    const v = formValues(form);
    const created = await api<{ token: string }>(path, {
      method: "POST",
      body: { name: v.name, expiresInDays: v.expiresInDays ? Number(v.expiresInDays) : null },
    });
    setSecret(created.token);
    tokens.reload();
  });
  const revoke = useAction(async (token: ApiToken) => {
    await api(`${path}/${token.id}`, { method: "DELETE" });
    setRevoking(undefined);
    tokens.reload();
  });
  const close = () => {
    setCreating(false);
    setSecret(undefined);
  };
  const newToken = (
    <Button variant="primary" onClick={() => setCreating(true)}>
      New token
    </Button>
  );
  if (tokens.error?.status === 403)
    return <EmptyState title="Only admins can manage API tokens" description="Ask an owner or admin of this organization to create a token for you." />;
  return (
    <div className="flex flex-col gap-8">
      <PageHeader
        title="API tokens"
        description="A token acts in this organization with its creator's role. It cannot manage tokens or SSO."
        actions={newToken}
      />
      <Loaded query={tokens} skeleton={<TableSkeleton />}>
        {(list) =>
          list.length === 0 ? (
            <EmptyState title="No API tokens" description="Create one to deploy from CI or script the Liftgate API." action={newToken} />
          ) : (
            <Table columns={["Name", "Created by", "Created", "Last used", "Expires", ""]}>
              {list.map((token) => (
                <Row key={token.id}>
                  <Cell className="font-medium">{token.name}</Cell>
                  <Cell className="text-graphite-200">{token.createdBy.login}</Cell>
                  <Cell className="text-graphite-400">{timeAgo(token.createdAt)}</Cell>
                  <Cell className="text-graphite-400">{lastUsed(token.lastUsedAt)}</Cell>
                  <Cell>
                    <Expires at={token.expiresAt} />
                  </Cell>
                  <Cell className="text-right">
                    <Button variant="danger" onClick={() => setRevoking(token)}>
                      Revoke
                    </Button>
                  </Cell>
                </Row>
              ))}
            </Table>
          )
        }
      </Loaded>
      <Dialog open={creating} title={secret ? "Copy your new token" : "New API token"} onClose={close}>
        {secret ? (
          <div className="flex flex-col gap-4">
            <p className="text-sm text-graphite-200">This is the only time the token is shown. Store it in your CI secrets before closing.</p>
            <CopyField label="Token" value={secret} />
            <div className="flex justify-end">
              <Button variant="primary" onClick={close}>
                Done
              </Button>
            </div>
          </div>
        ) : (
          <form
            onSubmit={(e) => {
              e.preventDefault();
              create.run(e.currentTarget);
            }}
            className="flex flex-col gap-4"
          >
            <Field label="Name" hint="What uses this token, for example github-actions">
              <Input name="name" required maxLength={100} />
            </Field>
            <Field label="Expires">
              <Select name="expiresInDays" defaultValue="90">
                {lifetimes.map((lifetime) => (
                  <option key={lifetime.days} value={lifetime.days}>
                    {lifetime.label}
                  </option>
                ))}
              </Select>
            </Field>
            <FormError message={create.error} />
            <div className="flex justify-end gap-2">
              <Button onClick={close}>Cancel</Button>
              <Button type="submit" variant="primary" pending={create.pending}>
                Create token
              </Button>
            </div>
          </form>
        )}
      </Dialog>
      <ConfirmDialog
        open={!!revoking}
        title="Revoke token"
        pending={revoke.pending}
        error={revoke.error}
        onConfirm={() => revoking && revoke.run(revoking)}
        onClose={() => setRevoking(undefined)}
      >
        Anything still using <span className="font-medium text-white">{revoking?.name}</span> is rejected from its next request. This cannot be undone.
      </ConfirmDialog>
    </div>
  );
}
