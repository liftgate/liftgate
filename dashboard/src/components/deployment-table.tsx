import type { Build, Deployment } from "@/lib/types";
import { shortSha, timeAgo } from "@/lib/util";
import { StatusBadge } from "./ui/badge";
import { Button } from "./ui/button";
import { Cell, Row, Table } from "./ui/table";

export function DeploymentTable({
  deployments,
  builds,
  replicas,
  rolling,
  onRollback,
}: {
  deployments: Deployment[];
  builds?: Build[];
  replicas: number;
  rolling?: string;
  onRollback?: (id: string) => void;
}) {
  return (
    <Table columns={["Status", "Build", "Ready", "Created", ""]}>
      {[...deployments]
        .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
        .map((deployment, i) => {
          const build = builds?.find((b) => b.id === deployment.buildId);
          return (
            <Row key={deployment.id}>
              <Cell>
                <div className="flex gap-2">
                  <StatusBadge status={deployment.status} />
                  {deployment.status === "running" && deployment.health && deployment.health !== "healthy" && <StatusBadge status={deployment.health} />}
                </div>
                {deployment.error && <p className="mt-1 max-w-xs text-xs text-danger">{deployment.error}</p>}
              </Cell>
              <Cell mono>
                {build ? shortSha(build.commitSha) : deployment.buildId.slice(0, 8)}
                {build?.commitMessage && <span className="ml-2 font-sans text-sm text-graphite-400">{build.commitMessage.split("\n")[0]}</span>}
              </Cell>
              <Cell>
                {deployment.replicasReady}/{replicas}
              </Cell>
              <Cell className="text-graphite-400">
                <span title={deployment.createdAt}>{timeAgo(deployment.createdAt)}</span>
              </Cell>
              <Cell className="text-right">
                {onRollback && i > 0 && !build?.imagePruned && (
                  <Button disabled={!!rolling} pending={rolling === deployment.id} onClick={() => onRollback(deployment.id)}>
                    Roll back to this
                  </Button>
                )}
              </Cell>
            </Row>
          );
        })}
    </Table>
  );
}
