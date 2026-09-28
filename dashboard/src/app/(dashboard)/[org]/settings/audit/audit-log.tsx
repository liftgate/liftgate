"use client";

import { useState } from "react";
import { api } from "@/lib/api";
import { useAction, useApi } from "@/lib/hooks";
import type { AuditEntry } from "@/lib/types";
import { timeAgo } from "@/lib/util";
import { Loaded } from "@/components/loaded";
import { PageHeader } from "@/components/page-header";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { FormError } from "@/components/ui/input";
import { TableSkeleton } from "@/components/ui/skeleton";
import { Cell, Row, Table } from "@/components/ui/table";

const PAGE = 50;

export function AuditLog({ org }: { org: string }) {
  const path = `/orgs/${org}/audit?limit=${PAGE}`;
  const latest = useApi<AuditEntry[]>(path);
  const [older, setOlder] = useState<AuditEntry[][]>([]);
  const more = useAction(async (before: number) => {
    const page = await api<AuditEntry[]>(`${path}&before=${before}`);
    setOlder((pages) => [...pages, page]);
  });
  if (latest.error?.status === 403)
    return <EmptyState title="Only admins can read the audit log" description="Ask an owner or admin of this organization what changed." />;
  return (
    <div className="flex flex-col gap-8">
      <PageHeader title="Audit log" description="Changes made in this organization from the dashboard and the API, newest first." />
      <Loaded query={latest} skeleton={<TableSkeleton rows={5} />}>
        {(first) => {
          const entries = [first, ...older].flat();
          const last = [first, ...older].at(-1) ?? [];
          if (entries.length === 0) return <EmptyState title="Nothing recorded yet" description="Changes to projects, services, members and tokens show up here." />;
          return (
            <>
              <Table columns={["When", "Actor", "Action", "Target", "Details"]}>
                {entries.map((entry) => (
                  <Row key={entry.id}>
                    <Cell className="whitespace-nowrap text-graphite-400">
                      <time dateTime={entry.createdAt} title={new Date(entry.createdAt).toLocaleString()}>
                        {timeAgo(entry.createdAt)}
                      </time>
                    </Cell>
                    <Cell>
                      <span className="flex items-center gap-2 whitespace-nowrap">
                        {entry.actor?.login ?? "Deleted user"}
                        {entry.viaToken && <Badge>API token</Badge>}
                      </span>
                    </Cell>
                    <Cell mono className="whitespace-nowrap">
                      {entry.action}
                    </Cell>
                    <Cell mono className="whitespace-nowrap text-graphite-400">
                      {entry.targetId}
                    </Cell>
                    <Cell className="text-graphite-400">
                      {Object.entries(entry.details)
                        .map(([name, value]) => `${name}: ${value}`)
                        .join(" · ")}
                    </Cell>
                  </Row>
                ))}
              </Table>
              <FormError message={more.error} />
              {last.length === PAGE && (
                <div className="flex justify-center">
                  <Button pending={more.pending} onClick={() => more.run(entries[entries.length - 1].id)}>
                    Load older entries
                  </Button>
                </div>
              )}
            </>
          );
        }}
      </Loaded>
    </div>
  );
}
