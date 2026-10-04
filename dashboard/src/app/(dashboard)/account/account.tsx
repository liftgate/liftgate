"use client";

import Image from "next/image";
import Link from "next/link";
import { useState } from "react";
import { api } from "@/lib/api";
import { useAction, useApi, type Query } from "@/lib/hooks";
import type { AuthProviders, GitConnection, Identity, OAuthProvider, Passkey, User } from "@/lib/types";
import { formValues, lastUsed, timeAgo } from "@/lib/util";
import { createPasskey } from "@/lib/webauthn";
import { Loaded } from "@/components/loaded";
import { DangerZone } from "@/components/danger-zone";
import { ProviderLabel, ProviderLink, providerNames } from "@/components/provider";
import { SettingsLayout } from "@/components/settings-layout";
import { Button, buttonClasses } from "@/components/ui/button";
import { Card, CardHeader } from "@/components/ui/card";
import { ConfirmDialog } from "@/components/ui/dialog";
import { EmptyState } from "@/components/ui/empty-state";
import { FormError, Input } from "@/components/ui/input";
import { Skeleton, TableSkeleton } from "@/components/ui/skeleton";
import { Cell, Row, Table } from "@/components/ui/table";

function useRemoval(reload: () => void) {
  const [target, setTarget] = useState<{ path: string; title: string; detail: string }>();
  const remove = useAction(async (path: string) => {
    await api(path, { method: "DELETE" });
    setTarget(undefined);
    reload();
  });
  return {
    ask: (path: string, title: string, detail: string) => setTarget({ path, title, detail }),
    dialog: (
      <ConfirmDialog
        open={!!target}
        title={target?.title ?? ""}
        pending={remove.pending}
        error={remove.error}
        onConfirm={() => target && remove.run(target.path)}
        onClose={() => setTarget(undefined)}
      >
        {target?.detail}
      </ConfirmDialog>
    ),
  };
}

const sections = [
  { id: "profile", label: "Profile", href: "/account" },
  { id: "sign-in", label: "Sign-in methods", href: "/account?section=sign-in" },
  { id: "git", label: "Git connections", href: "/account?section=git" },
];

export function Account({ section, error }: { section?: string; error?: string }) {
  const me = useApi<User>("/me");
  const providers = useApi<AuthProviders>("/auth/providers");
  const oauth = providers.data?.oauth ?? [];
  const current = sections.find((s) => s.id === section)?.id ?? "profile";
  return (
    <SettingsLayout title="Account" sections={sections} current={current}>
      <FormError message={error} />
      {current === "sign-in" && (
        <>
          <SignInMethods oauth={oauth} />
          <Passkeys />
        </>
      )}
      {current === "git" && <GitConnections github={oauth.includes("github")} />}
      {current === "profile" && (
        <>
          <Profile me={me} />
          <DeleteAccount login={me.data?.login} />
        </>
      )}
    </SettingsLayout>
  );
}

function Profile({ me }: { me: Query<User> }) {
  return (
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
              {user.operator && (
                <Link href="/dashboard/operator" className={buttonClasses("secondary", "ml-auto")}>
                  Operator console
                </Link>
              )}
            </div>
          )}
        </Loaded>
      </div>
    </Card>
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
                          onClick={() => remove.ask(path, "Remove sign-in method", `${providerNames[identity.provider]} no longer signs you in to this account.`)}
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
              <ProviderLink key={provider} provider={provider} intent="link" next="/account?section=sign-in">
                Link {providerNames[provider]}
              </ProviderLink>
            ))}
          </div>
        )}
      </div>
      {remove.dialog}
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
                        <Button variant="danger" onClick={() => remove.ask(path, "Remove passkey", `${passkey.name} no longer signs you in to this account.`)}>
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
            <Input name="name" required maxLength={64} aria-label="Passkey name" placeholder="MacBook Touch ID" className="min-w-0 max-w-sm flex-1" />
            <Button type="submit" variant="primary" pending={add.pending}>
              Add passkey
            </Button>
          </div>
          <FormError message={add.error} />
        </form>
      </div>
      {remove.dialog}
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
        <Loaded query={connections} skeleton={<TableSkeleton rows={1} />}>
          {(list) =>
            list.length === 0 ? (
              <EmptyState
                title="No git connections"
                description={github ? "Connect GitHub to import repositories into projects." : "GitHub is not configured on this Liftgate instance."}
                action={
                  github && (
                    <ProviderLink provider="github" intent="connect" next="/account?section=git">
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
                          onClick={() => remove.ask(path, `Disconnect ${providerNames[connection.provider]}`, "Reconnect it to import repositories again.")}
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
      {remove.dialog}
    </Card>
  );
}

function DeleteAccount({ login }: { login?: string }) {
  const remove = useAction(async () => {
    await api("/me", { method: "DELETE" });
    window.location.replace("/login");
  });
  return (
    <DangerZone
      title="Delete account"
      description="Removes your sign-in methods and sessions, and the organizations only you belong to with their projects and apps."
      typed={login}
      pending={remove.pending}
      error={remove.error}
      onConfirm={() => remove.run()}
    >
      Organizations you own with other members have to be deleted first. Everything else goes with your account.
    </DangerZone>
  );
}
