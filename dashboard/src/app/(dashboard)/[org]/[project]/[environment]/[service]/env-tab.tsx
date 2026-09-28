"use client";

import { useApi } from "@/lib/hooks";
import type { EnvVar, Service } from "@/lib/types";
import { EnvEditor } from "@/components/env-editor";
import { Loaded } from "@/components/loaded";
import { EmptyState } from "@/components/ui/empty-state";
import { TableSkeleton } from "@/components/ui/skeleton";
import { Cell, Row, Table } from "@/components/ui/table";

export function EnvTab({ service, admin }: { service: Service; admin: boolean }) {
  const vars = useApi<EnvVar[]>(`/services/${service.id}/env`);
  return (
    <Loaded query={vars} skeleton={<TableSkeleton />}>
      {(initial) =>
        admin ? (
          <EnvEditor serviceId={service.id} initial={initial} />
        ) : initial.length === 0 ? (
          <EmptyState title="No variables" description="An admin of this organization sets the variables." />
        ) : (
          <Table columns={["Name", "Value"]} label="Environment variables">
            {initial.map((v) => (
              <Row key={v.name}>
                <Cell mono>{v.name}</Cell>
                <Cell mono>{v.secret ? <span className="font-sans text-graphite-400">Secret</span> : v.value}</Cell>
              </Row>
            ))}
          </Table>
        )
      }
    </Loaded>
  );
}
