import Link from "next/link";
import { buttonClasses } from "@/components/ui/button";
import { LinkPending } from "./link-pending";

export function AppLink({ signedIn, size, className = "" }: { signedIn: boolean; size?: "lg"; className?: string }) {
  return (
    <Link href={signedIn ? "/dashboard" : "/login"} className={buttonClasses("primary", className, size)}>
      <LinkPending />
      {signedIn ? "Dashboard" : "Request access"}
    </Link>
  );
}
