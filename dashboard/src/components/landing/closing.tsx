import { ProviderGlyph } from "@/components/provider";
import { buttonClasses } from "@/components/ui/button";
import { AppLink } from "./app-link";
import { repoUrl } from "./links";
import { h2, lead } from "./styles";

export function Closing({ signedIn }: { signedIn: boolean }) {
  return (
    <section aria-labelledby="closing-title" className="mx-auto mt-24 max-w-2xl text-center md:mt-40 xl:mt-52">
      <h2 id="closing-title" className={h2}>
        Start with one repository
      </h2>
      <p className={`mt-4 ${lead}`}>
        Liftgate Cloud is free during the beta, with no uptime guarantee, and accounts are approved by hand. The source is on GitHub.
      </p>
      <div className="mt-8 flex flex-col justify-center gap-3 sm:flex-row">
        <AppLink signedIn={signedIn} size="lg" />
        <a href={repoUrl} target="_blank" rel="noreferrer" className={buttonClasses("secondary", "", "lg")}>
          <ProviderGlyph provider="github" />
          Source code
        </a>
      </div>
    </section>
  );
}
