"use client";

import { useState } from "react";
import { api } from "@/lib/api";
import { useApi } from "@/lib/hooks";
import type { GitHubRepository, ImportableRepositories } from "@/lib/types";
import { Loaded } from "./loaded";
import { ProviderLink } from "./provider";
import { Badge } from "./ui/badge";
import { Button, buttonClasses } from "./ui/button";
import { EmptyState } from "./ui/empty-state";
import { Input } from "./ui/input";
import { Skeleton } from "./ui/skeleton";

export function RepoPicker({ next, value, error, onChange }: { next: string; value?: string; error?: string; onChange: (repo: GitHubRepository) => void }) {
  const [round, setRound] = useState(0);
  const [query, setQuery] = useState("");
  const repos = useApi(`repositories:${round}`, () => api<ImportableRepositories>("/me/github/repositories"));
  const refresh = <Button onClick={() => setRound((r) => r + 1)}>Refresh</Button>;
  if (repos.error?.code === "github_not_connected")
    return (
      <EmptyState
        title="Connect GitHub to import a repository"
        description="Liftgate lists the repositories you can deploy through your GitHub connection."
        action={
          <ProviderLink provider="github" intent="connect" next={next}>
            Connect GitHub
          </ProviderLink>
        }
      />
    );
  return (
    <Loaded query={repos} skeleton={<Skeleton className="h-64" />}>
      {({ repositories, installUrl }) => {
        const install = (label: string, className = buttonClasses("primary")) => (
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
                  {install("Install the GitHub App")}
                  {refresh}
                </div>
              }
            />
          );
        const term = query.trim().toLowerCase();
        const shown = repositories.filter((repo) => repo.fullName === value || repo.fullName.toLowerCase().includes(term));
        return (
          <fieldset className="flex min-w-0 flex-col gap-2">
            <legend className="mb-2 text-sm font-medium text-graphite-200">Repository</legend>
            <div className="flex gap-2">
              <Input type="search" aria-label="Search repositories" placeholder="Search" value={query} onChange={(e) => setQuery(e.target.value)} className="min-w-0 flex-1" />
              {refresh}
            </div>
            <div className="max-h-64 divide-y divide-graphite-700 overflow-y-auto rounded-md border border-graphite-700">
              {shown.map((repo) => (
                <label key={repo.fullName} className="flex h-12 cursor-pointer items-center gap-3 px-3 hover:bg-graphite-800 has-checked:bg-graphite-800">
                  <input
                    type="radio"
                    name="repoFullName"
                    value={repo.fullName}
                    required
                    checked={repo.fullName === value}
                    onChange={() => onChange(repo)}
                    className="size-4 shrink-0 accent-accent"
                  />
                  <span className="min-w-0 flex-1 truncate font-mono text-xs">{repo.fullName}</span>
                  {repo.private && <Badge>private</Badge>}
                </label>
              ))}
              {shown.length === 0 && <p className="px-3 py-4 text-sm text-graphite-400">No repository matches {query.trim()}.</p>}
            </div>
            {error ? (
              <p role="alert" className="text-xs text-danger">
                {error}
              </p>
            ) : (
              <p className="text-xs text-graphite-400">
                Only repositories where the GitHub App is installed and you can push are listed.{" "}
                {install("Install it on another account", "text-graphite-200 underline underline-offset-2 hover:text-white")}
              </p>
            )}
          </fieldset>
        );
      }}
    </Loaded>
  );
}
