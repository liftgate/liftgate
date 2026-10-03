import { Fragment, type ReactNode } from "react";
import type { Build } from "@/lib/types";
import { canRollBack, currentDeployment, shortSha, timeAgo, type HistoryRow } from "@/lib/util";
import { StatusBadge } from "./ui/badge";
import { Button } from "./ui/button";
import { Cell, Row, Table } from "./ui/table";

export function DeploymentTable({
  rows,
  replicas,
  selected,
  onToggle,
  log,
  rolling,
  onRollback,
  readOnly = false,
}: {
  rows: HistoryRow[];
  replicas: number;
  selected?: string;
  onToggle?: (buildId: string) => void;
  log?: (build: Build) => ReactNode;
  rolling?: string;
  onRollback?: (id: string) => void;
  readOnly?: boolean;
}) {
  const current = currentDeployment(rows.flatMap((row) => (row.deployment ? [row.deployment] : [])));
  const open = rows.find((row) => row.build && row.build.id === selected);
  return (
    <Table columns={["Status", "Commit", "Ready", "Created", ""]}>
      {rows.map((row) => {
        const { build, deployment } = row;
        const created = deployment?.createdAt ?? build?.createdAt ?? "";
        const status = deployment?.status ?? build?.status;
        const error = deployment?.error ?? build?.error;
        return (
          <Fragment key={row.key}>
            <Row selected={row === open}>
              <Cell>
                <div className="flex gap-2">
                  {status && <StatusBadge status={status} />}
                  {deployment?.status === "running" && deployment.health && deployment.health !== "healthy" && <StatusBadge status={deployment.health} />}
                </div>
                {error && <p className="mt-1 max-w-xs text-xs text-danger">{error}</p>}
              </Cell>
              <Cell mono>
                {build ? shortSha(build.commitSha) : deployment?.buildId.slice(0, 8)}
                {build?.commitMessage && <span className="ml-2 font-sans text-sm text-graphite-400">{build.commitMessage.split("\n")[0]}</span>}
              </Cell>
              <Cell>{deployment && deployment === current && `${deployment.replicasReady}/${replicas}`}</Cell>
              <Cell className="text-graphite-400">
                <span title={created}>{timeAgo(created)}</span>
              </Cell>
              <Cell className="text-right">
                <div className="flex justify-end gap-2">
                  {!readOnly && deployment && canRollBack(deployment, build) && (
                    <Button disabled={!!rolling} pending={rolling === deployment.id} onClick={onRollback && (() => onRollback(deployment.id))}>
                      Roll back to this
                    </Button>
                  )}
                  {build && onToggle && rows.find((other) => other.build?.id === build.id) === row && (
                    <Button variant="ghost" aria-expanded={row === open} onClick={() => onToggle(build.id)}>
                      {row === open ? "Hide logs" : "Logs"}
                    </Button>
                  )}
                </div>
              </Cell>
            </Row>
            {row === open && build && log && (
              <tr>
                <td colSpan={5}>
                  <div className="sticky left-0 w-[100cqw] p-4">{log(build)}</div>
                </td>
              </tr>
            )}
          </Fragment>
        );
      })}
    </Table>
  );
}
