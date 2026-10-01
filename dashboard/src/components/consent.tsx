import type { AuthProviders } from "@/lib/types";
import { linkTarget } from "@/lib/util";

const LegalLink = ({ href, children }: { href: string; children: string }) => (
  <a href={href} {...linkTarget(href)} className="underline underline-offset-2 hover:text-white">
    {children}
  </a>
);

export function Consent({ providers }: { providers: AuthProviders }) {
  if (!providers.termsUrl) return null;
  return (
    <p className="text-center text-xs text-graphite-400">
      By continuing you agree to the <LegalLink href={providers.termsUrl}>Terms</LegalLink>
      {providers.aupUrl && <> and <LegalLink href={providers.aupUrl}>Acceptable Use Policy</LegalLink></>}.
    </p>
  );
}
