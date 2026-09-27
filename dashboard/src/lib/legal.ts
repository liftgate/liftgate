import type { AuthProviders } from "./types";

export const legalLinks = (providers?: AuthProviders) =>
  [
    { label: "Terms", href: providers?.termsUrl },
    { label: "Privacy", href: providers?.privacyUrl },
    { label: "Acceptable Use", href: providers?.aupUrl },
  ].filter((link): link is { label: string; href: string } => !!link.href);
