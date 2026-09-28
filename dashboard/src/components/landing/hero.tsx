import { buttonClasses } from "@/components/ui/button";
import { AppLink } from "./app-link";
import { Showreel } from "./showreel";
import { lead } from "./styles";

const transcript =
  "Liftgate showreel, 30 seconds, no sound. A developer types git push. A build log follows: cloning liftgate/hello, Railpack detects Node and pnpm, dependencies install, the image is built and pushed to the registry, the release is applied to Kubernetes, HTTPS traffic is routed, and the app is live at web-hello-dean.liftgate.app. A browser shows the app saying Hello, world. Four words follow: Frontend, for Next.js, Astro, SvelteKit and static sites; Backend, for Go, Kotlin, Rust, Node and Python; Workers, for queues, background jobs and long-running tasks; Cron, on a schedule of every six hours. One project with five services, web, api, worker, cron and docs, on a private network. Web services get a URL and HTTPS under liftgate.app. Open source: run it on your own Kubernetes, or on ours, installed with a Helm chart under the AGPL-3.0 licence. Built in: deploy on push, one-click rollbacks, branch environments, encrypted secrets, build logs, passkeys, SAML SSO, API tokens, monorepos, Dockerfiles and isolation. Liftgate. Full-stack hosting. Open source. liftgate.dev.";

export function Hero({ signedIn }: { signedIn: boolean }) {
  return (
    <div className="grid gap-10 pt-10 md:gap-12 md:pt-16 lg:grid-cols-12 lg:items-center lg:gap-x-6 xl:pt-20">
      <div className="max-w-xl lg:col-span-5">
        <h1 className="text-4xl leading-10 font-medium tracking-tighter md:text-5xl md:leading-14 xl:text-6xl xl:leading-16">
          <span className="block">Push to GitHub.</span>
          <span className="block">Liftgate runs it.</span>
        </h1>
        <p className={`mt-6 ${lead}`}>Open-source hosting for web apps, APIs, workers and cron jobs, on Liftgate Cloud or your own Kubernetes.</p>
        <div className="mt-8 flex flex-col gap-3 sm:flex-row">
          <AppLink signedIn={signedIn} size="lg" />
          <a href="#self-hosting" className={buttonClasses("secondary", "", "lg")}>
            Self-host
          </a>
        </div>
      </div>
      <div className="lg:col-span-7">
        <Showreel transcript={transcript} />
      </div>
    </div>
  );
}
