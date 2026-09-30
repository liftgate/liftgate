"use client";

import { usePathname } from "next/navigation";
import { useState, type ReactNode } from "react";
import { api } from "@/lib/api";
import { useAction, useApi, usePages } from "@/lib/hooks";
import type { OperatorOrg, OperatorSummary, OperatorUser, Organization, User, UserStatus } from "@/lib/types";
import { timeAgo } from "@/lib/util";
import { LoadMore } from "@/components/load-more";
import { Loaded } from "@/components/loaded";
import { PageHeader } from "@/components/page-header";
import { providerNames } from "@/components/provider";
import { Badge, StatusBadge } from "@/components/ui/badge";
import { Button, Spinner } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/dialog";
import { EmptyState } from "@/components/ui/empty-state";
import { FormError } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { TableSkeleton } from "@/components/ui/skeleton";
import { Cell, Row, Table } from "@/components/ui/table";
import { Tabs } from "@/components/ui/tabs";
import NotFound from "../../not-found";

const views = ["pending", "users", "orgs"] as const;

type View = (typeof views)[number];

type Action = { label: string; title: string; path: string; danger?: boolean; reason?: boolean; detail: ReactNode };

type Notice = { message: string };

const statuses: UserStatus[] = ["pending", "active", "suspended"];

const name = (text: string) => <span className="font-medium text-white">{text}</span>;

const mailed = "if this instance sends mail";

function accountActions(user: User): Action[] {
  const path = `/operator/users/${user.id}`;
  const approve = {
    label: "Approve",
    title: "Approve account",
    path: `${path}/approve`,
    detail: <>{name(user.login)} can create organizations and accept invitations, and is emailed {mailed}.</>,
  };
  const suspend = {
    label: "Suspend",
    title: "Suspend account",
    path: `${path}/suspend`,
    danger: true,
    reason: true,
    detail: <>{name(user.login)} is signed out everywhere, loses their API tokens and cannot sign in until you unsuspend the account. The reason is emailed to them {mailed}.</>,
  };
  const unsuspend = {
    label: "Unsuspend",
    title: "Unsuspend account",
    path: `${path}/unsuspend`,
    detail: <>{name(user.login)} can sign in again. Sessions and API tokens deleted by the suspension stay deleted.</>,
  };
  return { pending: [approve, suspend], active: [suspend], suspended: [unsuspend] }[user.status];
}

function orgAction(org: Organization): Action {
  const path = `/operator/orgs/${org.slug}`;
  return org.suspendedAt
    ? {
        label: "Unsuspend",
        title: "Unsuspend organization",
        path: `${path}/unsuspend`,
        detail: <>The apps of {name(org.name)} start again with their configured replicas and routes.</>,
      }
    : {
        label: "Suspend",
        title: "Suspend organization",
        path: `${path}/suspend`,
        danger: true,
        reason: true,
        detail: (
          <>
            Every app of {name(org.name)} stops, its queued builds are cancelled and its members cannot change it until you unsuspend it. Its owners are
            emailed the reason {mailed}.
          </>
        ),
      };
}

export function OperatorConsole({ view: initial }: { view?: string | string[] }) {
  const pathname = usePathname();
  const [view, setView] = useState<View>(views.find((v) => v === initial) ?? "pending");
  const summary = useApi<OperatorSummary>("/operator/summary");
  const [action, setAction] = useState<Action>();
  const [notice, setNotice] = useState<string>();
  const [round, setRound] = useState(0);
  const done = (message: string) => {
    setNotice(message);
    summary.reload();
    setRound((r) => r + 1);
  };
  const confirm = useAction(async (reason: string) => {
    if (!action) return;
    const { message } = await api<Notice>(action.path, { method: "POST", body: action.reason ? { reason } : undefined });
    setAction(undefined);
    done(message);
  });
  if (summary.error?.status === 404) return <NotFound />;
  const show = (id: View) => {
    setView(id);
    window.history.replaceState(null, "", `${pathname}?view=${id}`);
  };
  return (
    <div className="flex flex-col gap-8">
      <PageHeader title="Operator" description="Approve new accounts, suspend accounts and organizations, and move organizations between plans on this Liftgate instance." />
      <Loaded query={summary} skeleton={<TableSkeleton />}>
        {({ pending, plans }) => (
          <>
            {notice && (
              <p role="status" className="text-sm text-graphite-200">
                {notice}
              </p>
            )}
            <Tabs
              items={[
                {
                  id: "pending",
                  label: (
                    <span className="inline-flex items-center gap-2">
                      Pending
                      {pending > 0 && <Badge tone="warning">{pending}</Badge>}
                    </span>
                  ),
                },
                { id: "users", label: "Users" },
                { id: "orgs", label: "Organizations" },
              ]}
              value={view}
              label="Operator views"
              onChange={show}
            >
              {view === "orgs" ? <Organizations key={round} plans={plans} act={setAction} done={done} /> : <Accounts key={`${view}-${round}`} pending={view === "pending"} act={setAction} />}
            </Tabs>
          </>
        )}
      </Loaded>
      <ConfirmDialog
        open={!!action}
        title={action?.title ?? ""}
        variant={action?.danger ? "danger" : "primary"}
        prompt={action?.reason ? "Reason" : undefined}
        pending={confirm.pending}
        error={confirm.error}
        onConfirm={confirm.run}
        onClose={() => setAction(undefined)}
      >
        {action?.detail}
      </ConfirmDialog>
    </div>
  );
}

function Accounts({ pending, act }: { pending: boolean; act: (action: Action) => void }) {
  const [status, setStatus] = useState(pending ? "pending" : "");
  const accounts = usePages<OperatorUser>(`/operator/users${status ? `?status=${status}` : ""}`, (account) => account.user.id);
  return (
    <div className="flex flex-col gap-4">
      {!pending && (
        <div className="flex justify-end">
          <Select aria-label="Status" value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="">All statuses</option>
            {statuses.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </Select>
        </div>
      )}
      <Loaded query={accounts.query} skeleton={<TableSkeleton />}>
        {() =>
          accounts.items.length === 0 ? (
            pending ? (
              <EmptyState title="No accounts are waiting for approval" description="New sign-ups wait here while sign-up needs approval." />
            ) : (
              <EmptyState title={status ? `No ${status} accounts` : "No accounts"} />
            )
          ) : (
            <>
              <Table
                label={pending ? "Pending accounts" : "Accounts"}
                columns={["Account", "Email", "Signs in with", ...(pending ? [] : ["Organizations"]), "Signed up", ...(pending ? [] : ["Status"]), ""]}
              >
                {accounts.items.map(({ user, providers, orgs, createdAt }) => (
                  <Row key={user.id}>
                    <Cell>
                      <p className="font-medium">{user.login}</p>
                      {user.name && <p className="text-xs text-graphite-400">{user.name}</p>}
                    </Cell>
                    <Cell className="text-graphite-200">{user.email ?? "—"}</Cell>
                    <Cell className="whitespace-nowrap text-graphite-400">{providers.map((p) => providerNames[p]).join(", ") || "Passkey"}</Cell>
                    {!pending && <Cell className="text-graphite-400">{orgs}</Cell>}
                    <Cell className="whitespace-nowrap text-graphite-400">{timeAgo(createdAt)}</Cell>
                    {!pending && (
                      <Cell>
                        <StatusBadge status={user.status} />
                      </Cell>
                    )}
                    <Cell>
                      <span className="flex justify-end gap-2">
                        {accountActions(user).map((action) => (
                          <Button key={action.label} variant={action.danger ? "danger" : "secondary"} onClick={() => act(action)}>
                            {action.label}
                          </Button>
                        ))}
                      </span>
                    </Cell>
                  </Row>
                ))}
              </Table>
              <LoadMore pages={accounts}>Load more accounts</LoadMore>
            </>
          )
        }
      </Loaded>
    </div>
  );
}

function Organizations({ plans, act, done }: { plans: string[]; act: (action: Action) => void; done: (message: string) => void }) {
  const orgs = usePages<OperatorOrg>("/operator/orgs", (entry) => entry.org.id);
  const [changing, setChanging] = useState<{ slug: string; plan: string }>();
  const move = useAction(async (slug: string, plan: string) => {
    try {
      done((await api<Notice>(`/operator/orgs/${slug}/plan`, { method: "PUT", body: { plan } })).message);
    } finally {
      setChanging(undefined);
    }
  });
  return (
    <div className="flex flex-col gap-4">
      <FormError message={move.error} />
      <Loaded query={orgs.query} skeleton={<TableSkeleton />}>
        {() =>
          orgs.items.length === 0 ? (
            <EmptyState title="No organizations yet" description="Organizations appear here once an approved account creates one." />
          ) : (
            <>
              <Table label="Organizations" columns={["Organization", "Plan", "Members", "Projects", "Services", "Created", "Status", ""]}>
                {orgs.items.map(({ org, members, projects, services, createdAt }) => {
                  const saving = changing?.slug === org.slug;
                  const action = orgAction(org);
                  return (
                    <Row key={org.id}>
                      <Cell>
                        <p className="font-medium">{org.name}</p>
                        <p className="text-xs text-graphite-400">{org.slug}</p>
                      </Cell>
                      <Cell>
                        <span className="flex items-center gap-2">
                          <Select
                            aria-label={`Plan of ${org.slug}`}
                            value={saving ? changing.plan : org.plan}
                            disabled={move.pending}
                            onChange={(e) => {
                              setChanging({ slug: org.slug, plan: e.target.value });
                              move.run(org.slug, e.target.value);
                            }}
                          >
                            {[...new Set([...plans, org.plan])].map((plan) => (
                              <option key={plan} value={plan}>
                                {plan}
                              </option>
                            ))}
                          </Select>
                          {saving && <Spinner />}
                        </span>
                      </Cell>
                      <Cell className="text-graphite-400">{members}</Cell>
                      <Cell className="text-graphite-400">{projects}</Cell>
                      <Cell className="text-graphite-400">{services}</Cell>
                      <Cell className="whitespace-nowrap text-graphite-400">{timeAgo(createdAt)}</Cell>
                      <Cell>
                        <StatusBadge status={org.suspendedAt ? "suspended" : "active"} />
                        {org.suspendedReason && <p className="mt-1 text-xs text-graphite-400">{org.suspendedReason}</p>}
                      </Cell>
                      <Cell className="text-right">
                        <Button variant={action.danger ? "danger" : "secondary"} onClick={() => act(action)}>
                          {action.label}
                        </Button>
                      </Cell>
                    </Row>
                  );
                })}
              </Table>
              <LoadMore pages={orgs}>Load more organizations</LoadMore>
            </>
          )
        }
      </Loaded>
    </div>
  );
}
