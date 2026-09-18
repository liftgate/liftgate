"use client";

import { useEffect, useId, useState, type ReactNode } from "react";
import { apiHref } from "@/lib/api";
import type { IdentityProvider, OAuthProvider } from "@/lib/types";
import { buttonClasses, Spinner } from "./ui/button";

export const providerNames: Record<IdentityProvider, string> = {
  github: "GitHub",
  google: "Google",
  gitlab: "GitLab",
  bitbucket: "Bitbucket",
  email: "Email",
  saml: "SAML SSO",
};

function BitbucketGlyph() {
  const gradient = useId();
  return (
    <svg viewBox="0 0 62.42 62.42" aria-hidden className="size-4">
      <defs>
        <linearGradient id={gradient} x1="64.01" y1="30.27" x2="32.99" y2="54.48" gradientUnits="userSpaceOnUse">
          <stop offset="0.18" stopColor="#0052cc" />
          <stop offset="1" stopColor="#2684ff" />
        </linearGradient>
      </defs>
      <g transform="translate(0 -3.13)">
        <path
          fill="#2684ff"
          d="M2 6.26A2 2 0 0 0 0 8.58l8.49 51.54a2.72 2.72 0 0 0 2.66 2.27h40.73a2 2 0 0 0 2-1.68L62.37 8.59a2 2 0 0 0-2-2.32ZM37.75 43.51h-13l-3.52-18.39H40.9Z"
        />
        <path fill={`url(#${gradient})`} d="M59.67 25.12H40.9l-3.15 18.39h-13L9.4 61.73a2.71 2.71 0 0 0 1.75.66h40.74a2 2 0 0 0 2-1.68Z" />
      </g>
    </svg>
  );
}

const glyphs: Partial<Record<IdentityProvider, ReactNode>> = {
  github: (
    <svg viewBox="0 0 16 16" aria-hidden className="size-4" fill="currentColor">
      <path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0 0 16 8c0-4.42-3.58-8-8-8Z" />
    </svg>
  ),
  google: (
    <svg viewBox="0 0 48 48" aria-hidden className="size-4">
      <path fill="#EA4335" d="M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z" />
      <path fill="#4285F4" d="M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z" />
      <path fill="#FBBC05" d="M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z" />
      <path fill="#34A853" d="M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z" />
    </svg>
  ),
  gitlab: (
    <svg viewBox="0 0 25 24" aria-hidden className="size-4">
      <path fill="#E24329" d="m24.507 9.5-.034-.09L21.082.562a.896.896 0 0 0-1.694.091l-2.29 7.01H7.825L5.535.653a.898.898 0 0 0-1.694-.09L.451 9.411.416 9.5a6.297 6.297 0 0 0 2.09 7.278l.012.01.03.022 5.16 3.867 2.56 1.935 1.554 1.176a1.051 1.051 0 0 0 1.268 0l1.555-1.176 2.56-1.935 5.197-3.89.014-.01A6.297 6.297 0 0 0 24.507 9.5Z" />
      <path fill="#FC6D26" d="m24.507 9.5-.034-.09a11.44 11.44 0 0 0-4.56 2.051l-7.447 5.632 4.742 3.584 5.197-3.89.014-.01A6.297 6.297 0 0 0 24.507 9.5Z" />
      <path fill="#FCA326" d="m7.707 20.677 2.56 1.935 1.555 1.176a1.051 1.051 0 0 0 1.268 0l1.555-1.176 2.56-1.935-4.743-3.584-4.755 3.584Z" />
      <path fill="#FC6D26" d="M5.01 11.461a11.43 11.43 0 0 0-4.56-2.05L.416 9.5a6.297 6.297 0 0 0 2.09 7.278l.012.01.03.022 5.16 3.867 4.745-3.584-7.444-5.632Z" />
    </svg>
  ),
  bitbucket: <BitbucketGlyph />,
};

export const ProviderGlyph = ({ provider }: { provider: IdentityProvider }) => glyphs[provider] ?? null;

export const ProviderLabel = ({ provider }: { provider: IdentityProvider }) => (
  <span className="flex items-center gap-2 font-medium">
    <ProviderGlyph provider={provider} />
    {providerNames[provider]}
  </span>
);

export function ProviderLink({
  provider,
  intent,
  next,
  className = "",
  children,
}: {
  provider: OAuthProvider;
  intent: "signin" | "link" | "connect";
  next: string;
  className?: string;
  children: ReactNode;
}) {
  const [pending, setPending] = useState(false);
  useEffect(() => {
    const reset = () => setPending(false);
    window.addEventListener("pageshow", reset);
    return () => window.removeEventListener("pageshow", reset);
  }, []);
  return (
    <a
      href={apiHref(`/auth/${provider}/login?${new URLSearchParams({ next, intent })}`)}
      onClick={() => setPending(true)}
      aria-busy={pending}
      className={buttonClasses("secondary", `${pending ? "pointer-events-none opacity-70" : ""} ${className}`)}
    >
      {pending ? <Spinner /> : <ProviderGlyph provider={provider} />}
      {children}
    </a>
  );
}
