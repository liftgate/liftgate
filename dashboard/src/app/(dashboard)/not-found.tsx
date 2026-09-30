import type { Metadata } from "next";
import Link from "next/link";
import { buttonClasses } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";

export const metadata: Metadata = { title: "Page not found" };

export default function NotFound() {
  return (
    <EmptyState
      title="Page not found"
      description="The address does not match any organization, project or service."
      action={
        <Link href="/dashboard" className={buttonClasses()}>
          Back to the dashboard
        </Link>
      }
    />
  );
}
