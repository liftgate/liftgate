import Link from "next/link";
import type { ReactNode } from "react";
import type { Environment, Service } from "@/lib/types";
import { environmentKindLabels, shortSha, timeAgo } from "@/lib/util";
import { Badge, StatusBadge } from "./ui/badge";
import { Card, CardHeader } from "./ui/card";
import { Cell, Row, Table } from "./ui/table";

export function EnvironmentCard({ environment, services, href, children }: { environment: Environment; services: Service[]; href: string; children?: ReactNode }) {
  return (
    <Card>
      <CardHeader title={environment.name} description={environment.branch} actions={<Badge>{environmentKindLabels[environment.kind]}</Badge>} />
      <div className="p-6">
        {services.length === 0 ? (
          <p className="text-sm text-graphite-400">No services in this environment.</p>
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
