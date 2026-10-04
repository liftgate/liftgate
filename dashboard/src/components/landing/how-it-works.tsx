import type { ReactNode } from "react";
import { DeploymentTable } from "@/components/deployment-table";
import type { Build, Deployment } from "@/lib/types";
import { history } from "@/lib/util";
import { BuildLogPreview } from "./build-log-preview";
import { buildLog } from "./demo";
import { Section } from "./section";
import { h3 } from "./styles";

export function HowItWorks({ builds, deployments }: { builds: Build[]; deployments: Deployment[] }) {
  const steps: { title: string; body: string; caption: string; figure: ReactNode }[] = [
    {
      title: "Push",
      body: "The GitHub App tells Liftgate about the push. Every service in an environment that tracks the branch gets a build.",
      caption: "The Deployments tab of an example service, with a build running.",
      figure: (
        <div inert className="max-md:[&_:is(th,td):nth-child(n+3)]:hidden md:rounded-lg md:border md:border-graphite-700 md:bg-graphite-950 md:p-6">
          <DeploymentTable rows={history(builds, [])} replicas={1} selected={builds[0].id} />
        </div>
      ),
    },
    {
      title: "Build",
      body: "Liftgate builds your Dockerfile, or Railpack works out the build when there is none. The log streams while the build runs.",
      caption: "Railpack's plan for an example Next.js app, replayed.",
      figure: <BuildLogPreview title="Build 4f2c9e1" lines={buildLog} />,
    },
    {
      title: "Release",
      body: "A successful build rolls out automatically. Web and static services answer over HTTPS on liftgate.app. Roll back to any of the last ten successful builds.",
      caption: "The Deployments tab once the build is released.",
      figure: (
        <div inert className="max-md:[&_:is(th,td):not(:first-child,:last-child)]:hidden md:rounded-lg md:border md:border-graphite-700 md:bg-graphite-950 md:p-6">
          <DeploymentTable rows={history(builds, deployments)} replicas={1} />
        </div>
      ),
    },
  ];
  return (
    <Section id="how-it-works" title="What happens when you push">
      <ol className="mt-8 flex flex-col gap-y-12 md:mt-12 md:gap-y-16 xl:gap-y-24">
        {steps.map((step) => (
          <li key={step.title} className="grid grid-cols-1 gap-6 lg:grid-cols-12">
            <div className="max-w-xl lg:sticky lg:top-24 lg:col-span-4 lg:self-start">
              <h3 className={h3}>{step.title}</h3>
              <p className="mt-2 text-base leading-6 text-graphite-400">{step.body}</p>
            </div>
            <figure className="lg:col-span-8">
              {step.figure}
              <figcaption className="mt-3 text-sm leading-5 text-graphite-400">{step.caption}</figcaption>
            </figure>
          </li>
        ))}
      </ol>
    </Section>
  );
}
