import type { ReactNode } from "react";
import type { Build } from "@/lib/types";
import { shortSha, timeAgo } from "@/lib/util";
import { StatusBadge } from "./ui/badge";
import { Button } from "./ui/button";
import { Card, CardHeader } from "./ui/card";
import { Cell, Row, Table } from "./ui/table";

export function BuildsCard({ actions, children }: { actions?: ReactNode; children: ReactNode }) {
  return (
    <Card>
      <CardHeader
        title="Builds"
        description="Every push to the tracked branch queues a build. Start one by hand from a branch or commit."
        actions={actions}
      />
      <div className="flex flex-col gap-4 p-6">{children}</div>
    </Card>
  );
}

export function BuildTable({ builds, selected, onToggle }: { builds: Build[]; selected?: string | null; onToggle?: (id: string) => void }) {
  return (
    <Table columns={["Status", "Commit", "Branch", "Created", ""]}>
      {builds.map((build) => (
        <Row key={build.id} selected={build.id === selected}>
          <Cell>
            <StatusBadge status={build.status} />
          </Cell>
          <Cell mono>
            {shortSha(build.commitSha)}
            {build.commitMessage && <span className="ml-2 font-sans text-sm text-graphite-400">{build.commitMessage.split("\n")[0]}</span>}
          </Cell>
          <Cell mono>{build.branch}</Cell>
          <Cell className="text-graphite-400">
            <span title={build.createdAt}>{timeAgo(build.createdAt)}</span>
          </Cell>
          <Cell className="text-right">
            <Button variant="ghost" onClick={onToggle && (() => onToggle(build.id))}>
              {build.id === selected ? "Hide logs" : "Logs"}
            </Button>
          </Cell>
        </Row>
      ))}
    </Table>
  );
}
