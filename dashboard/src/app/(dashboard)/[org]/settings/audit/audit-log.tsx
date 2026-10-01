"use client";

import { usePages } from "@/lib/hooks";
import type { AuditEntry } from "@/lib/types";
import { timeAgo } from "@/lib/util";
import { LoadMore } from "@/components/load-more";
import { Loaded } from "@/components/loaded";
import { PageHeader } from "@/components/page-header";
import { Badge } from "@/components/ui/badge";
import { EmptyState } from "@/components/ui/empty-state";
import { TableSkeleton } from "@/components/ui/skeleton";
import { Cell, Row, Table } from "@/components/ui/table";

export function AuditLog({ org }: { org: string }) {
  const log = usePages<AuditEntry>(`/orgs/${org}/audit`, (entry) => entry.id);
  return (
    <div className="flex flex-col gap-8">
      <PageHeader title="Audit log" description="Changes made in this organization from the dashboard and the API, newest first." />
      {log.query.error?.status === 403 ? (
        <EmptyState title="Only admins can read the audit log" description="Ask an owner or admin of this organization what changed." />
      ) : (
        <Loaded query={log.query} skeleton={<TableSkeleton rows={5} />}>
          {() => {
            const entries = log.items;
            if (entries.length === 0) return <EmptyState title="Nothing recorded yet" description="Changes to projects, services, members and tokens show up here." />;
            return (
              <>
                <Table label="Audit log entries" columns={["When", "Actor", "Action", "Target", "Details"]}>
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
                <LoadMore pages={log}>Load older entries</LoadMore>
              </>
            );
          }}
        </Loaded>
      )}
    </div>
  );
}
