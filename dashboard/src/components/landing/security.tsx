import type { ReactNode } from "react";
import { EnvEditor } from "@/components/env-editor";
import { envVars } from "./demo";
import { sourceUrl } from "./links";
import { Section } from "./section";
import { sourceLink } from "./styles";

const podSpec = `.withNewSpec()
.withRuntimeClassName(runtimeClass)
.withNodeSelector<String, String>(nodeSelector)
.withTolerations(tolerations)
.withRestartPolicy(restartPolicy)
.withAutomountServiceAccountToken(false)
.withEnableServiceLinks(false)
.withNewSecurityContext()
.withRunAsNonRoot(true).withRunAsUser(TENANT_UID).withRunAsGroup(TENANT_UID).withFsGroup(TENANT_UID)
.withNewSeccompProfile().withType("RuntimeDefault").endSeccompProfile()
.endSecurityContext()
.withContainers(container(r))
.endSpec()`;

const line = (code: string) => (
  <div className="px-6 pb-6">
    <code className="block font-mono text-xs leading-5 text-graphite-200">{code}</code>
  </div>
);

const resources = "control-plane/src/main/kotlin/dev/liftgate/k8s/Resources.kt";

const rules: { title: string; body: string; path: string; lines: [number, number]; className: string; textClassName?: string; visual: ReactNode }[] = [
  {
    title: "A gVisor sandbox around every app",
    body: "Every app runs under gVisor, the chart's default runtime, as a non-root user with every capability dropped and no Kubernetes API token.",
    path: resources,
    lines: [228, 263],
    className: "bg-graphite-900 md:col-span-2 lg:col-span-7 lg:row-span-2",
    visual: (
      <div className="flex flex-1 pl-6 mask-r-from-[calc(100%-4rem)]">
        <pre className="min-w-0 flex-1 overflow-hidden rounded-tl-lg border-t border-l border-graphite-700 bg-graphite-950 p-4 font-mono text-xs leading-5 text-graphite-200">{podSpec}</pre>
      </div>
    ),
  },
  {
    title: "Read-only access to your repositories",
    body: "The GitHub App asks only for read permissions. Each build clones with a token limited to that one repository.",
    path: "control-plane/src/main/kotlin/dev/liftgate/build/GitHubApp.kt",
    lines: [37, 42],
    className: "bg-graphite-950 lg:col-span-5",
    visual: line('mapOf("contents" to "read", "metadata" to "read")'),
  },
  {
    title: "A namespace per environment",
    body: "Each environment gets its own namespace with a default-deny network policy. Apps reach each other inside it, but not other private networks or mail ports.",
    path: resources,
    lines: [102, 107],
    className: "bg-graphite-950 lg:col-span-5",
    visual: line('"10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10", "169.254.0.0/16"'),
  },
  {
    title: "Secrets you can set but not read back",
    body: "Variables are encrypted with AES-256-GCM and injected at runtime, not at build time. The API never sends a secret value back.",
    path: "control-plane/src/main/kotlin/dev/liftgate/secret/SecretBox.kt",
    lines: [15, 33],
    className: "bg-graphite-950 md:col-span-2 lg:col-span-12 xl:flex-row",
    textClassName: "xl:w-96 xl:shrink-0",
    visual: (
      <div className="pb-6 pl-6 max-md:mask-r-from-[calc(100%-4rem)] md:pr-6 xl:min-w-0 xl:flex-1 xl:pt-6 xl:pl-0">
        <div inert className="w-192 md:w-auto">
          <EnvEditor serviceId="demo" initial={envVars} />
        </div>
      </div>
    ),
  },
];

export function Security() {
  return (
    <Section id="security" title="Isolation you can check in the source" body="Liftgate treats every app as untrusted code. Each rule below links to the file that enforces it.">
      <div className="mt-8 grid gap-4 md:mt-12 md:grid-cols-2 md:gap-6 lg:grid-cols-12">
        {rules.map((rule) => {
          const file = rule.path.split("/").pop();
          return (
            <div key={rule.title} className={`flex flex-col overflow-hidden rounded-lg border border-graphite-700 ${rule.className}`}>
              <div className={`p-6 ${rule.textClassName ?? ""}`}>
                <h3 className="text-base leading-6 font-medium">{rule.title}</h3>
                <p className="mt-1 text-sm leading-5 text-graphite-400">{rule.body}</p>
                <a
                  href={sourceUrl(rule.path, ...rule.lines)}
                  target="_blank"
                  rel="noreferrer"
                  aria-label={`${file} on GitHub`}
                  className={`mt-3 inline-block ${sourceLink}`}
                >
                  {file}
                </a>
              </div>
              {rule.visual}
            </div>
          );
        })}
      </div>
    </Section>
  );
}
