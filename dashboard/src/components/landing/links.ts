export const repoUrl = "https://github.com/liftgate/liftgate";

export const sourceUrl = (path: string, from?: number, to?: number) => `${repoUrl}/blob/main/${path}${from ? `#L${from}-L${to}` : ""}`;

export const docsPages = ["getting-started", "runtime-contract", "custom-domains", "plans-and-limits", "self-hosting"] as const;

export type DocsPage = (typeof docsPages)[number];

export const docsUrl = (page: DocsPage, version = process.env.NEXT_PUBLIC_LIFTGATE_VERSION) =>
  `${repoUrl}/blob/${version && /^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$/.test(version) ? `v${version}` : "main"}/documentation/${page}.md`;
