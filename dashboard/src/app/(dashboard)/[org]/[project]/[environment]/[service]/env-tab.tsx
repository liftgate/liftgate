"use client";

import { useApi } from "@/lib/hooks";
import type { EnvVar, Service } from "@/lib/types";
import { EnvEditor } from "@/components/env-editor";
import { Loaded } from "@/components/loaded";
import { TableSkeleton } from "@/components/ui/skeleton";

export function EnvTab({ service }: { service: Service }) {
  const vars = useApi<EnvVar[]>(`/services/${service.id}/env`);
  return (
    <Loaded query={vars} skeleton={<TableSkeleton />}>
      {(initial) => <EnvEditor serviceId={service.id} initial={initial} />}
    </Loaded>
  );
}
