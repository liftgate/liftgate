import { buttonClasses } from "@/components/ui/button";
import { CopyField } from "@/components/ui/copy-field";
import { repoUrl, sourceUrl } from "./links";
import { Section } from "./section";
import { h3, sourceLink } from "./styles";

const steps = [
  {
    label: "Get the source",
    value: `git clone ${repoUrl} && cd liftgate`,
    hint: "The baseline script runs from a checkout.",
  },
  {
    label: "Install the cluster baseline",
    value: "LIFTGATE_INSTALL_CILIUM=1 LETSENCRYPT_EMAIL=you@example.com sh infra/install.sh",
    hint: "Adds the Gateway API, Cilium, the gVisor runtime class, cert-manager, CloudNativePG and Prometheus, and skips anything already installed. It stops before changing anything on a cluster that runs another CNI. If that cluster has a Gateway API implementation, leave out LIFTGATE_INSTALL_CILIUM=1 and set the chart's gateway.className.",
  },
];

export function SelfHost() {
  return (
    <Section
      id="self-hosting"
      title="Install it on your own cluster"
      body="The chart installs the control plane, the dashboard, NATS and a PostgreSQL cluster. You bring Kubernetes, a container registry and two domains."
    >
      <div className="max-w-3xl">
        <h3 className={`mt-12 ${h3}`}>You will need</h3>
        <ul className="mt-4 flex flex-col gap-3 text-base leading-6 text-graphite-200">
          <li>
            A Kubernetes cluster with gVisor on every node.{" "}
            <a href={sourceUrl("infra/k3s/install.md")} target="_blank" rel="noreferrer" className={sourceLink}>
              infra/k3s/install.md
            </a>{" "}
            sets one up with k3s.
          </li>
          <li>A container registry that build jobs push to and your nodes pull from.</li>
          <li>Wildcard DNS and a wildcard TLS certificate for your apps domain, kept on a different registrable domain from the dashboard.</li>
          <li>A GitHub App for sign-in, push webhooks and cloning.</li>
        </ul>
        <h3 className={`mt-12 ${h3}`}>Install</h3>
        <ol className="mt-4 flex flex-col gap-6">
          {steps.map((step) => (
            <li key={step.label}>
              <CopyField {...step} />
            </li>
          ))}
        </ol>
        <p className="mt-8 text-sm leading-5 text-graphite-400">Liftgate is in public beta, so read each release&apos;s upgrade notes and back up before you upgrade.</p>
        <a href={`${repoUrl}#self-hosting`} target="_blank" rel="noreferrer" className={buttonClasses("secondary", "mt-6", "lg")}>
          Install guide
        </a>
      </div>
    </Section>
  );
}
