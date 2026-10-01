"use client";

import Link from "next/link";
import { useEffect, useEffectEvent, useState } from "react";
import { useApi } from "@/lib/hooks";
import type { ImportableRepositories } from "@/lib/types";
import { Loaded } from "./loaded";
import { ProviderLink } from "./provider";
import { Badge } from "./ui/badge";
import { Button, buttonClasses } from "./ui/button";
import { EmptyState } from "./ui/empty-state";
import { Input } from "./ui/input";
import { Skeleton } from "./ui/skeleton";

const rowClasses = "flex h-12 items-center gap-3 px-3";

export function RepoPicker({ org, disabled = false }: { org: string; disabled?: boolean }) {
  const [query, setQuery] = useState("");
  const repos = useApi<ImportableRepositories>("/me/github/repositories");
  const refetch = useEffectEvent(() => repos.reload());
  useEffect(() => {
    const onFocus = () => refetch();
    window.addEventListener("focus", onFocus);
    return () => window.removeEventListener("focus", onFocus);
  }, []);
  if (repos.error?.code === "github_not_connected")
    return (
      <EmptyState
        title="Connect GitHub to import a repository"
        description="Liftgate lists the repositories you can deploy through your GitHub connection."
        action={
          <ProviderLink provider="github" intent="connect" next={`/new?org=${org}`}>
            Connect GitHub
          </ProviderLink>
        }
      />
    );
  return (
    <Loaded
      query={repos}
      skeleton={
        <div aria-busy className="divide-y divide-graphite-700 rounded-lg border border-graphite-700">
          {Array.from({ length: 5 }, (_, i) => (
            <div key={i} className={rowClasses}>
              <Skeleton className="h-4 w-48" />
              <Skeleton className="ml-auto h-8 w-16" />
            </div>
          ))}
        </div>
      }
    >
      {({ repositories, installUrl }) => {
        const install = (label: string, className: string) => (
          <a href={installUrl} target="_blank" rel="noreferrer" className={className}>
            {label}
          </a>
        );
        if (repositories.length === 0)
          return (
            <EmptyState
              title="Install the GitHub App"
              description="Liftgate deploys repositories where its GitHub App is installed and you can push. Install it on your account or organization, then refresh."
              action={
                <div className="flex flex-wrap justify-center gap-2">
                  {install("Install the GitHub App", buttonClasses("primary"))}
                  <Button onClick={repos.reload}>Refresh</Button>
                </div>
              }
            />
          );
        const term = query.trim().toLowerCase();
        const shown = repositories.filter((repo) => repo.fullName.toLowerCase().includes(term));
        return (
          <div className="flex flex-col gap-4">
            <Input type="search" aria-label="Search repositories" placeholder="Search" value={query} onChange={(e) => setQuery(e.target.value)} />
            <ul aria-label="Repositories" className="divide-y divide-graphite-700 rounded-lg border border-graphite-700">
              {shown.map((repo) => (
                <li key={repo.fullName} className={`relative ${rowClasses} ${disabled ? "" : "hover:bg-graphite-800"}`}>
                  <span className="min-w-0 flex-1 truncate font-mono text-xs">{repo.fullName}</span>
                  <span className="max-w-24 shrink-0 truncate font-mono text-xs text-graphite-400 max-sm:hidden">{repo.defaultBranch}</span>
                  {repo.private && <Badge>private</Badge>}
                  {disabled ? (
                    <Button disabled aria-label={`Import ${repo.fullName}`}>
                      Import
                    </Button>
                  ) : (
                    <Link href={`/new?org=${org}&repo=${repo.fullName}`} aria-label={`Import ${repo.fullName}`} className={buttonClasses("secondary", "after:absolute after:inset-0")}>
                      Import
                    </Link>
                  )}
                </li>
              ))}
              {shown.length === 0 && <li className="px-3 py-4 text-sm text-graphite-400">No repository matches {query.trim()}.</li>}
            </ul>
            <p className="text-xs text-graphite-400">
              Missing a repository? {install("Install the GitHub App on another account", "text-graphite-200 underline underline-offset-2 hover:text-white")}
            </p>
          </div>
        );
      }}
    </Loaded>
  );
}
