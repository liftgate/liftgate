import Link from "next/link";
import type { Environment, Service } from "@/lib/types";
import { StatusBadge } from "./ui/badge";
import { Card, CardHeader } from "./ui/card";
import { EmptyState } from "./ui/empty-state";
import { Cell, Row, Table } from "./ui/table";

export function EnvironmentCard({ environment, services, href }: { environment: Environment; services: Service[]; href: string }) {
  return (
    <Card>
      <CardHeader
        title={environment.name}
        description={`${environment.branch} · ${environment.namespace}`}
        actions={<StatusBadge status={environment.kind} />}
      />
      <div className="p-6">
        {services.length === 0 ? (
          <EmptyState title="No services in this environment" description="Add a web, worker, cron or static service." />
        ) : (
          <Table columns={["Service", "Kind", "Resources", "Replicas"]}>
            {services.map((service) => (
              <Row key={service.id}>
                <Cell>
                  <Link href={`${href}/${service.slug}`} className="font-medium hover:text-accent">
                    {service.name}
                  </Link>
                </Cell>
                <Cell>
                  <StatusBadge status={service.kind} />
                </Cell>
                <Cell className="text-graphite-400">
                  {service.cpuMillis}m CPU · {service.memoryMb} MB
                </Cell>
                <Cell>{service.replicas}</Cell>
              </Row>
            ))}
          </Table>
        )}
      </div>
    </Card>
  );
}
