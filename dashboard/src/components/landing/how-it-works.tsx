import type { ReactNode } from "react";
import { BuildsCard, BuildTable } from "@/components/build-table";
import { DeploymentTable } from "@/components/deployment-table";
import type { Build, Deployment } from "@/lib/types";
import { BuildLogPreview } from "./build-log-preview";
import { buildLog } from "./demo";
import { Section } from "./section";
import { h3 } from "./styles";

const crop = "-mr-6 overflow-hidden md:mr-0 md:overflow-visible";

export function HowItWorks({ builds, deployments }: { builds: Build[]; deployments: Deployment[] }) {
  const steps: { title: string; body: string; caption: string; figure: ReactNode }[] = [
    {
      title: "Push",
      body: "The GitHub App tells Liftgate about the push. Every service in an environment that tracks the branch gets a build.",
      caption: "The Builds tab of an example service, with a build running.",
      figure: (
        <div inert className="min-w-140">
          <BuildsCard>
            <BuildTable builds={builds} selected={builds[0].id} />
          </BuildsCard>
        </div>
      ),
    },
    {
      title: "Build",
      body: "Liftgate builds your Dockerfile, or Railpack works out the build when there is none. The log streams while the build runs.",
      caption: "Railpack's plan for an example Next.js app, replayed.",
      figure: (
        <div className="min-w-140">
          <BuildLogPreview title="Build 4f2c9e1" lines={buildLog} />
        </div>
      ),
    },
    {
      title: "Release",
      body: "A successful build rolls out automatically. Web and static services answer over HTTPS on liftgate.app. Roll back to any of the last ten successful builds.",
      caption: "The Deployments tab once the build is released.",
      figure: (
        <div className="min-w-140 rounded-lg border border-graphite-700 bg-graphite-950 p-6">
          <div inert>
            <DeploymentTable deployments={deployments} builds={builds} replicas={1} />
          </div>
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
              <div className={crop}>{step.figure}</div>
              <figcaption className="mt-3 text-sm leading-5 text-graphite-400">{step.caption}</figcaption>
            </figure>
          </li>
        ))}
      </ol>
    </Section>
  );
}
