"use client";

import Link from "next/link";
import type { ReactNode } from "react";
import { usePathname } from "next/navigation";
import { useApi, useSignOut } from "@/lib/hooks";
import type { User } from "@/lib/types";
import { Mark } from "./mark";
import { Button } from "./ui/button";

export function Nav({ actions }: { actions?: ReactNode }) {
  const onLogin = usePathname().startsWith("/login");
  const inApp = !onLogin && !actions;
  const me = useApi<User>(inApp && "/me");
  const signOut = useSignOut();
  return (
    <header className="border-b border-graphite-700 bg-graphite-900">
      <div className="mx-auto flex h-14 max-w-6xl items-center justify-between gap-4 px-6 sm:gap-6">
        <nav className="flex min-w-0 items-center gap-2 text-sm">
          <Link href={inApp ? "/dashboard" : "/"} className="flex shrink-0 items-center gap-2 font-semibold">
            <Mark />
            Liftgate
          </Link>
        </nav>
        {!onLogin && (
          <div className="flex items-center gap-2 sm:gap-4">
            {me.data && (
              <Link href="/account" className="text-sm text-graphite-400 hover:text-white">
                {me.data.login}
              </Link>
            )}
            {actions ?? (
              <Button variant="ghost" pending={signOut.pending} onClick={() => signOut.run()}>
                Sign out
              </Button>
            )}
          </div>
        )}
      </div>
    </header>
  );
}
