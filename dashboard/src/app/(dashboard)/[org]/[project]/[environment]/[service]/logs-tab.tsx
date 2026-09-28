import { LogViewer } from "@/components/log-viewer";
import type { Service } from "@/lib/types";

export function LogsTab({ service }: { service: Service }) {
  return <LogViewer path={`/logs/services/${service.id}`} title="Runtime logs" />;
}
