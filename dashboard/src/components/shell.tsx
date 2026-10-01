"use client";

import { usePathname, useSearchParams } from "next/navigation";
import { Suspense, useState, type ReactNode } from "react";
import { Footer } from "./footer";
import { Icon } from "./icons";
import { Mark } from "./mark";
import { Nav } from "./nav";
import { Sidebar, useScope } from "./sidebar";
import { TermsGate } from "./terms-gate";
import { buttonClasses } from "./ui/button";
import { Dialog } from "./ui/dialog";

const content = "mx-auto flex w-full max-w-5xl flex-1 flex-col px-6 py-8 lg:px-8";

export function Shell({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  if (pathname.startsWith("/login") || pathname === "/dashboard")
    return (
      <>
        <Nav />
        <main className="mx-auto flex w-full max-w-6xl flex-1 flex-col px-6 py-8">
          <TermsGate>{children}</TermsGate>
        </main>
        <Footer />
      </>
    );
  return (
    <Suspense fallback={<main className={content}>{children}</main>}>
      <AppShell>{children}</AppShell>
    </Suspense>
  );
}

function AppShell({ children }: { children: ReactNode }) {
  const scope = useScope();
  const route = `${usePathname()}?${useSearchParams()}`;
  const [drawer, setDrawer] = useState(false);
  const [shown, setShown] = useState(route);
  if (shown !== route) {
    setShown(route);
    setDrawer(false);
  }
  const suspended = scope.org?.suspendedAt && scope.org;
  return (
    <>
      <a href="#content" className={buttonClasses("secondary", "fixed top-3 left-6 z-50 -translate-y-16 focus:translate-y-0")}>
        Skip to content
      </a>
      <aside className="fixed inset-y-0 left-0 z-30 hidden w-60 overflow-y-auto border-r border-graphite-700 bg-graphite-900 lg:block">
        <Sidebar scope={scope} />
      </aside>
      <div className="flex flex-1 flex-col lg:pl-60">
        <header className="flex h-14 items-center gap-2 border-b border-graphite-700 bg-graphite-900 px-2 lg:hidden">
          <button
            type="button"
            aria-label="Open menu"
            onClick={() => setDrawer(true)}
            className="flex size-10 shrink-0 items-center justify-center rounded-md text-graphite-200 hover:bg-graphite-800 hover:text-white focus-visible:outline-2 focus-visible:outline-accent"
          >
            <Icon name="menu" className="size-5" />
          </button>
          <Mark />
          <span className="min-w-0 truncate text-sm font-medium">{scope.title}</span>
        </header>
        <main id="content" className={content}>
          {suspended && (
            <p role="status" className="mb-8 rounded-md border border-danger/40 bg-danger/10 px-4 py-3 text-sm text-danger">
              {suspended.name} is suspended{suspended.suspendedReason && ` for ${suspended.suspendedReason}`}. Its apps are stopped and changes are blocked until the operator lifts
              the suspension.
            </p>
          )}
          <TermsGate>{children}</TermsGate>
        </main>
      </div>
      <Dialog open={drawer} title="Menu" placement="left" onClose={() => setDrawer(false)}>
        <Sidebar scope={scope} />
      </Dialog>
    </>
  );
}
