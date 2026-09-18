"use client";

import Image from "next/image";
import { useState } from "react";
import { api } from "@/lib/api";
import { useAction, useApi } from "@/lib/hooks";
import type { AuthProviders, GitConnection, Identity, OAuthProvider, Passkey, User } from "@/lib/types";
import { formValues, timeAgo } from "@/lib/util";
import { createPasskey } from "@/lib/webauthn";
import { Loaded } from "@/components/loaded";
import { PageHeader } from "@/components/page-header";
import { ProviderLabel, ProviderLink, providerNames } from "@/components/provider";
import { Button } from "@/components/ui/button";
import { Card, CardHeader } from "@/components/ui/card";
import { EmptyState } from "@/components/ui/empty-state";
import { FormError, Input } from "@/components/ui/input";
import { Skeleton, TableSkeleton } from "@/components/ui/skeleton";
import { Cell, Row, Table } from "@/components/ui/table";

const lastUsed = (iso: string | null) => (iso ? timeAgo(iso) : "Never");

function useRemoval(reload: () => void) {
  const [target, setTarget] = useState<string>();
  const action = useAction(async (path: string, question: string) => {
    if (!window.confirm(question)) return;
    setTarget(path);
    await api(path, { method: "DELETE" });
    reload();
  });
  return { ...action, removing: (path: string) => action.pending && target === path };
}

export function Account({ error }: { error?: string }) {
  const me = useApi<User>("/me");
  const providers = useApi<AuthProviders>("/auth/providers");
  const oauth = providers.data?.oauth ?? [];
  return (
    <div className="flex flex-col gap-8">
      <PageHeader title="Account" description="Your profile, the ways you sign in, and the git accounts Liftgate can read." />
      <FormError message={error} />
      <Card>
        <CardHeader title="Profile" />
        <div className="p-6">
          <Loaded query={me} skeleton={<Skeleton className="h-12 w-64" />}>
            {(user) => (
              <div className="flex items-center gap-4">
                {user.avatarUrl && <Image src={user.avatarUrl} alt="" width={48} height={48} unoptimized className="size-12 rounded-full" />}
                <div className="min-w-0">
                  <p className="font-medium">{user.name ?? user.login}</p>
                  <p className="truncate text-sm text-graphite-400">
                    @{user.login}
                    {user.email && ` · ${user.email}`}
                  </p>
                </div>
              </div>
            )}
          </Loaded>
        </div>
      </Card>
      <SignInMethods oauth={oauth} />
      <Passkeys />
      <GitConnections github={oauth.includes("github")} />
    </div>
  );
}

function SignInMethods({ oauth }: { oauth: OAuthProvider[] }) {
  const identities = useApi<Identity[]>("/me/identities");
  const remove = useRemoval(identities.reload);
  const linkable = oauth.filter((provider) => identities.data && !identities.data.some((i) => i.provider === provider));
  return (
    <Card>
      <CardHeader title="Sign-in methods" description="Any of these signs you in to this account. Passkeys are listed separately." />
      <div className="flex flex-col gap-4 p-6">
        <FormError message={remove.error} />
        <Loaded query={identities} skeleton={<TableSkeleton rows={2} />}>
          {(list) =>
            list.length === 0 ? (
              <EmptyState title="No linked providers" description="You sign in with a passkey only. Link a provider as a fallback." />
            ) : (
              <Table columns={["Method", "Email", "Added", "Last used", ""]}>
                {list.map((identity) => {
                  const path = `/me/identities/${identity.id}`;
                  return (
                    <Row key={identity.id}>
                      <Cell>
                        <ProviderLabel provider={identity.provider} />
                      </Cell>
                      <Cell className="text-graphite-200">{identity.email ?? "—"}</Cell>
                      <Cell className="text-graphite-400">{timeAgo(identity.createdAt)}</Cell>
                      <Cell className="text-graphite-400">{lastUsed(identity.lastUsedAt)}</Cell>
                      <Cell className="text-right">
                        <Button
                          variant="danger"
                          pending={remove.removing(path)}
                          onClick={() => remove.run(path, `Remove ${providerNames[identity.provider]} as a sign-in method?`)}
                        >
                          Remove
                        </Button>
                      </Cell>
                    </Row>
                  );
                })}
              </Table>
            )
          }
        </Loaded>
        {linkable.length > 0 && (
          <div className="flex flex-wrap gap-2">
            {linkable.map((provider) => (
              <ProviderLink key={provider} provider={provider} intent="link" next="/account">
                Link {providerNames[provider]}
              </ProviderLink>
            ))}
          </div>
        )}
      </div>
    </Card>
  );
}

function Passkeys() {
  const passkeys = useApi<Passkey[]>("/me/passkeys");
  const remove = useRemoval(passkeys.reload);
  const add = useAction(async (form: HTMLFormElement) => {
    const { name } = formValues(form);
    const options = await api<{ publicKey: PublicKeyCredentialCreationOptionsJSON }>("/me/passkeys/options", { method: "POST" });
    await api("/me/passkeys", { method: "POST", body: { credential: await createPasskey(options), name } });
    form.reset();
    passkeys.reload();
  });
  return (
    <Card>
      <CardHeader title="Passkeys" description="Sign in with Touch ID, Windows Hello, a phone or a security key." />
      <div className="flex flex-col gap-4 p-6">
        <FormError message={remove.error} />
        <Loaded query={passkeys} skeleton={<TableSkeleton rows={1} />}>
          {(list) =>
            list.length === 0 ? (
              <EmptyState title="No passkeys yet" description="Add one below to sign in from this device without a provider." />
            ) : (
              <Table columns={["Name", "Added", "Last used", ""]}>
                {list.map((passkey) => {
                  const path = `/me/passkeys/${passkey.id}`;
                  return (
                    <Row key={passkey.id}>
                      <Cell className="font-medium">{passkey.name}</Cell>
                      <Cell className="text-graphite-400">{timeAgo(passkey.createdAt)}</Cell>
                      <Cell className="text-graphite-400">{lastUsed(passkey.lastUsedAt)}</Cell>
                      <Cell className="text-right">
                        <Button variant="danger" pending={remove.removing(path)} onClick={() => remove.run(path, `Remove the passkey ${passkey.name}?`)}>
                          Remove
                        </Button>
                      </Cell>
                    </Row>
                  );
                })}
              </Table>
            )
          }
        </Loaded>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            add.run(e.currentTarget);
          }}
          className="flex flex-col gap-2"
        >
          <div className="flex gap-2">
            <Input name="name" required maxLength={64} aria-label="Passkey name" placeholder="MacBook Touch ID" className="max-w-sm flex-1" />
            <Button type="submit" variant="primary" pending={add.pending}>
              Add passkey
            </Button>
          </div>
          <FormError message={add.error} />
        </form>
      </div>
    </Card>
  );
}

function GitConnections({ github }: { github: boolean }) {
  const connections = useApi<GitConnection[]>("/me/connections");
  const remove = useRemoval(connections.reload);
  return (
    <Card>
      <CardHeader title="Git connections" description="Used to list and import repositories. A git connection does not sign you in." />
      <div className="flex flex-col gap-4 p-6">
        <FormError message={remove.error} />
        <Loaded query={connections} skeleton={<TableSkeleton rows={1} />}>
          {(list) =>
            list.length === 0 ? (
              <EmptyState
                title="No git connections"
                description={github ? "Connect GitHub to import repositories into projects." : "GitHub is not configured on this Liftgate instance."}
                action={
                  github && (
                    <ProviderLink provider="github" intent="connect" next="/account">
                      Connect GitHub
                    </ProviderLink>
                  )
                }
              />
            ) : (
              <Table columns={["Provider", "Account", "Connected", ""]}>
                {list.map((connection) => {
                  const path = `/me/connections/${connection.provider}`;
                  return (
                    <Row key={connection.provider}>
                      <Cell>
                        <ProviderLabel provider={connection.provider} />
                      </Cell>
                      <Cell mono>{connection.accountLogin}</Cell>
                      <Cell className="text-graphite-400">{timeAgo(connection.connectedAt)}</Cell>
                      <Cell className="text-right">
                        <Button
                          variant="danger"
                          pending={remove.removing(path)}
                          onClick={() => remove.run(path, `Disconnect ${providerNames[connection.provider]}? Reconnect it to import repositories again.`)}
                        >
                          Disconnect
                        </Button>
                      </Cell>
                    </Row>
                  );
                })}
              </Table>
            )
          }
        </Loaded>
      </div>
    </Card>
  );
}
