import { EnvironmentCard } from "@/components/environment-card";
import { Card, CardHeader } from "@/components/ui/card";
import { Field, Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { environment, services } from "./demo";
import { Section } from "./section";

export function OneProject() {
  return (
    <Section
      id="one-project"
      title="Web apps, workers and cron jobs in one project"
      body={
        <>
          Every service picks its kind, root directory and resources. Workers reach your web services by name, like{" "}
          <code className="font-mono text-graphite-200">http://api</code>, and cron runs never overlap.
        </>
      }
    >
      <figure className="mt-8 grid grid-cols-1 gap-6 md:mt-12 lg:grid-cols-12">
        <div inert className="max-md:[&_:is(th,td):last-child]:hidden lg:col-span-9 lg:row-start-1">
          <EnvironmentCard environment={environment} services={services} href="#" />
        </div>
        <div inert className="lg:col-span-7 lg:col-start-6 lg:row-start-2 lg:-mt-12">
          <Card className="lg:shadow-2xl">
            <CardHeader title="Service settings" description="Save keeps changes for the next deploy. Save and redeploy applies them now without a rebuild." />
            <div className="grid grid-cols-2 gap-4 p-6">
              <Field label="Kind">
                <Select defaultValue="cron">
                  <option value="cron">cron</option>
                </Select>
              </Field>
              <Field label="Root directory">
                <Input defaultValue="/apps/api" className="font-mono" />
              </Field>
              <Field label="Cron schedule" hint="Cron services only">
                <Input defaultValue="0 6 * * *" className="font-mono" />
              </Field>
              <Field label="Start command" hint="Overrides the image entrypoint">
                <Input defaultValue="pnpm digest" className="font-mono" />
              </Field>
            </div>
          </Card>
        </div>
        <figcaption className="text-sm leading-5 text-graphite-400 lg:col-span-12">
          An example project&apos;s production environment, which tracks main, and the settings of its cron job.
        </figcaption>
      </figure>
    </Section>
  );
}
