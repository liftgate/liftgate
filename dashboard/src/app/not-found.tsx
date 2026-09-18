import Link from "next/link";
import { buttonClasses } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";

export default function NotFound() {
  return (
    <EmptyState
      title="Page not found"
      description="The address does not match any organization, project or service."
      action={
        <Link href="/" className={buttonClasses()}>
          Back to the dashboard
        </Link>
      }
    />
  );
}
