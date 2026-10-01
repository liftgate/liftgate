import Link from "next/link";
import type { ReactNode } from "react";
import type { Environment, Service } from "@/lib/types";
import { shortSha, timeAgo } from "@/lib/util";
import { StatusBadge } from "./ui/badge";
import { Button } from "./ui/button";
import { Card, CardHeader } from "./ui/card";
import { EmptyState } from "./ui/empty-state";
import { Cell, Row, Table } from "./ui/table";

export function EnvironmentCard({
  environment,
  services,
  href,
  onNewService,
  children,
}: {
  environment: Environment;
  services: Service[];
  href: string;
  onNewService?: () => void;
  children?: ReactNode;
}) {
  return (
    <Card>
      <CardHeader
        title={environment.name}
        description={`${environment.branch} · ${environment.namespace}`}
        actions={<StatusBadge status={environment.kind} />}
      />
      <div className="p-6">
        {services.length === 0 ? (
          <EmptyState
            title="No services in this environment"
            description="Add a web, worker, cron or static service."
            action={onNewService && <Button onClick={onNewService}>New service</Button>}
          />
        ) : (
          <Table columns={["Service", "Status", "URL", "Last deploy"]}>
            {services.map((service) => (
              <Row key={service.id}>
                <Cell>
                  <Link href={`${href}/${service.slug}`} className="font-medium hover:text-accent">
                    {service.name}
                  </Link>
                </Cell>
                <Cell>
                  <StatusBadge status={service.current?.status ?? "not deployed"} />
                </Cell>
                <Cell mono>
                  {service.url ? (
                    <a href={service.url} target="_blank" rel="noreferrer" className="hover:text-accent">
                      {service.url.replace("https://", "")}
                    </a>
                  ) : (
                    <span className="font-sans text-sm text-graphite-400">None</span>
                  )}
                </Cell>
                <Cell className="whitespace-nowrap text-graphite-400">
                  {service.current ? (
                    <span title={service.current.createdAt}>
                      {timeAgo(service.current.createdAt)} · <span className="font-mono text-xs">{shortSha(service.current.commitSha)}</span>
                    </span>
                  ) : (
                    "Never"
                  )}
                </Cell>
              </Row>
            ))}
          </Table>
        )}
      </div>
      {children}
    </Card>
  );
}
