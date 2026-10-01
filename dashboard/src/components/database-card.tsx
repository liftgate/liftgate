"use client";

import { useId, useState, type ReactNode } from "react";
import { api } from "@/lib/api";
import { useAction, useApi, usePolling } from "@/lib/hooks";
import type { Backup, Database, Environment, Service } from "@/lib/types";
import { formValues, timeAgo } from "@/lib/util";
import { DocsLink } from "./docs-link";
import { Loaded } from "./loaded";
import { saved } from "./save-actions";
import { StatusBadge } from "./ui/badge";
import { Button } from "./ui/button";
import { CopyField } from "./ui/copy-field";
import { ConfirmDialog, Dialog } from "./ui/dialog";
import { Field, FormError, Input } from "./ui/input";
import { Select } from "./ui/select";
import { Skeleton, TableSkeleton } from "./ui/skeleton";
import { Cell, Row, Table } from "./ui/table";

const slugPattern = "(?=.{2,40}$)[a-z][a-z0-9]*(-[a-z0-9]+)*";

export function DatabaseCard({ environment, services, admin, storage }: { environment: Environment; services: Service[]; admin: boolean; storage?: boolean }) {
  const path = `/environments/${environment.id}/databases`;
  const databases = useApi<Database[]>(path);
  const [adding, setAdding] = useState(false);
  const [selected, setSelected] = useState<string>();
  const heading = useId();
  usePolling(!!databases.data?.some((d) => !d.ready), databases.reload);
  const create = useAction(async (form: HTMLFormElement) => {
    const v = formValues(form);
    await api(path, { method: "POST", body: { slug: v.slug, storageGb: Number(v.storageGb), cpuMillis: Number(v.cpuMillis), memoryMb: Number(v.memoryMb) } });
    setAdding(false);
    databases.reload();
  });
  const serviceName = (id: string) => services.find((s) => s.id === id)?.name ?? "a deleted service";
  const database = databases.data?.find((d) => d.id === selected);
  return (
    <section aria-labelledby={heading} className="flex flex-col gap-4 border-t border-graphite-700 p-6">
      <div className="flex items-center justify-between gap-4">
        <h3 id={heading} className="text-sm font-medium">
          Databases
        </h3>
        {admin && storage && <Button onClick={() => setAdding(true)}>Add database</Button>}
      </div>
      <Loaded query={databases} skeleton={<TableSkeleton rows={1} />}>
        {(list) =>
          list.length === 0 ? (
            <p className="text-sm text-graphite-400">
              {storage === false ? (
                <>
                  Databases need a storage class that enforces capacity, and this installation has none configured.{" "}
                  <DocsLink page="self-hosting">Storage in the self-hosting guide</DocsLink>
                </>
              ) : (
                "Managed PostgreSQL for this environment. A linked service gets its connection URL as a variable."
              )}
            </p>
          ) : (
            <Table columns={["Database", "Status", "Storage", "Linked to", ""]} label={`Databases in ${environment.name}`}>
              {list.map((d) => (
                <Row key={d.id}>
                  <Cell mono>{d.slug}</Cell>
                  <Cell>
                    <StatusBadge status={d.ready ? "ready" : "starting"} />
                  </Cell>
                  <Cell className="whitespace-nowrap text-graphite-400">{d.storageGb} GB</Cell>
                  <Cell className="text-graphite-400">{d.links.length ? d.links.map((l) => `${serviceName(l.serviceId)} as ${l.envName}`).join(", ") : "No services"}</Cell>
                  <Cell className="text-right">
                    <Button onClick={() => setSelected(d.id)}>Manage</Button>
                  </Cell>
                </Row>
              ))}
            </Table>
          )
        }
      </Loaded>
      <Dialog open={adding} title="Add database" onClose={() => setAdding(false)}>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            create.run(e.currentTarget);
          }}
          className="flex flex-col gap-4"
        >
          <Field label="Name" hint="2 to 40 lowercase letters, numbers and dashes, starting with a letter" error={create.field === "slug" ? create.error : undefined}>
            <Input name="slug" required maxLength={40} pattern={slugPattern} defaultValue="db" className="font-mono" />
          </Field>
          <div className="grid gap-4 sm:grid-cols-3">
            <Field label="Storage (GB)">
              <Input name="storageGb" type="number" min={1} max={100} required defaultValue={1} />
            </Field>
            <Field label="CPU (millicores)">
              <Input name="cpuMillis" type="number" min={100} max={4000} required defaultValue={500} />
            </Field>
            <Field label="Memory (MB)">
              <Input name="memoryMb" type="number" min={256} max={8192} required defaultValue={512} />
            </Field>
          </div>
          <FormError message={create.field === "slug" ? undefined : create.error} />
          <div className="flex justify-end gap-2">
            <Button onClick={() => setAdding(false)}>Cancel</Button>
            <Button type="submit" variant="primary" pending={create.pending}>
              Add database
            </Button>
          </div>
        </form>
      </Dialog>
      <Dialog open={!!database} title={`Database ${database?.slug ?? ""}`} onClose={() => setSelected(undefined)}>
        {database && (
          <DatabaseDetails
            database={database}
            services={services}
            serviceName={serviceName}
            admin={admin}
            storage={!!storage}
            onChanged={databases.reload}
            onClose={() => setSelected(undefined)}
          />
        )}
      </Dialog>
    </section>
  );
}

function DatabaseDetails({
  database,
  services,
  serviceName,
  admin,
  storage,
  onChanged,
  onClose,
}: {
  database: Database;
  services: Service[];
  serviceName: (id: string) => string;
  admin: boolean;
  storage: boolean;
  onChanged: () => void;
  onClose: () => void;
}) {
  const connection = useApi<{ uri: string }>(admin && database.ready && `/databases/${database.id}/connection`);
  const backups = useApi<Backup[]>(`/databases/${database.id}/backups`);
  const [status, setStatus] = useState<string>();
  const [deleting, setDeleting] = useState(false);
  const linkable = services.filter((s) => !database.links.some((l) => l.serviceId === s.id));
  const link = useAction(async (form: HTMLFormElement) => {
    const v = formValues(form);
    await api(`/databases/${database.id}/links`, { method: "POST", body: { serviceId: v.serviceId, envName: v.envName } });
    setStatus(await saved(v.serviceId));
    onChanged();
  });
  const unlink = useAction(async (serviceId: string) => {
    await api(`/databases/${database.id}/links/${serviceId}`, { method: "DELETE" });
    setStatus(await saved(serviceId));
    onChanged();
  });
  const restore = useAction(async (form: HTMLFormElement) => {
    const v = formValues(form);
    await api(`/databases/${database.id}/restore`, { method: "POST", body: { slug: v.slug, pointInTime: new Date(v.pointInTime).toISOString() } });
    onChanged();
    onClose();
  });
  const remove = useAction(async () => {
    await api(`/databases/${database.id}`, { method: "DELETE" });
    onChanged();
    onClose();
  });
  return (
    <div className="flex flex-col gap-6">
      <Part title="Connection">
        {!database.ready ? (
          <p className="text-sm text-graphite-400">The connection URL appears once the database is ready.</p>
        ) : !admin ? (
          <p className="text-sm text-graphite-400">Only owners and admins can reveal the connection URL.</p>
        ) : (
          <Loaded query={connection} skeleton={<Skeleton className="h-8 w-full" />}>
            {({ uri }) => <CopyField label="Connection URL" value={uri} hint="Reachable from services in this environment only" />}
          </Loaded>
        )}
      </Part>
      <Part title="Linked services">
        {database.links.length === 0 && <p className="text-sm text-graphite-400">No service is linked yet.</p>}
        {database.links.map((l) => (
          <div key={`${l.serviceId}-${l.envName}`} className="flex items-center justify-between gap-4 text-sm">
            <span>
              {serviceName(l.serviceId)} as <span className="font-mono text-xs">{l.envName}</span>
            </span>
            {admin && (
              <Button variant="ghost" pending={unlink.pending} onClick={() => unlink.run(l.serviceId)}>
                Unlink
              </Button>
            )}
          </div>
        ))}
        {admin && linkable.length > 0 && (
          <form
            onSubmit={(e) => {
              e.preventDefault();
              link.run(e.currentTarget);
            }}
            className="grid items-end gap-4 sm:grid-cols-[1fr_1fr_auto]"
          >
            <Field label="Service">
              <Select name="serviceId">
                {linkable.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.name}
                  </option>
                ))}
              </Select>
            </Field>
            <Field label="Variable">
              <Input name="envName" required pattern="[A-Za-z_][A-Za-z0-9_]*" defaultValue="DATABASE_URL" className="font-mono" />
            </Field>
            <Button type="submit" pending={link.pending}>
              Link and redeploy
            </Button>
          </form>
        )}
        <FormError message={link.error ?? unlink.error} />
        {status && (
          <p role="status" className="text-sm text-graphite-400">
            {status}
          </p>
        )}
      </Part>
      <Part title="Backups">
        <Loaded query={backups} skeleton={<TableSkeleton rows={1} />}>
          {(list) =>
            list.length === 0 ? (
              <p className="text-sm text-graphite-400">No backups yet. The first one starts with the database when backups are configured.</p>
            ) : (
              <Table columns={["Started", "Status"]} label={`Backups of ${database.slug}`}>
                {list.map((b) => (
                  <Row key={b.name}>
                    <Cell className="whitespace-nowrap">{b.startedAt ? <span title={b.startedAt}>{timeAgo(b.startedAt)}</span> : "Waiting"}</Cell>
                    <Cell>
                      <StatusBadge status={b.phase ?? "pending"} />
                    </Cell>
                  </Row>
                ))}
              </Table>
            )
          }
        </Loaded>
        {admin && storage && backups.data?.some((b) => b.phase === "completed") && (
          <form
            onSubmit={(e) => {
              e.preventDefault();
              restore.run(e.currentTarget);
            }}
            className="flex flex-col gap-4"
          >
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label="Restore to" hint="Your local time, after the first completed backup" error={restore.field === "pointInTime" ? restore.error : undefined}>
                <Input name="pointInTime" type="datetime-local" step={1} required />
              </Field>
              <Field label="New database" error={restore.field === "slug" ? restore.error : undefined}>
                <Input name="slug" required maxLength={40} pattern={slugPattern} defaultValue={`${database.slug}-restored`.slice(0, 40)} className="font-mono" />
              </Field>
            </div>
            <FormError message={restore.field === "pointInTime" || restore.field === "slug" ? undefined : restore.error} />
            <div className="flex justify-end">
              <Button type="submit" pending={restore.pending}>
                Restore into a new database
              </Button>
            </div>
          </form>
        )}
      </Part>
      <div className="flex justify-end gap-2">
        {admin && (
          <Button variant="danger" onClick={() => setDeleting(true)}>
            Delete database
          </Button>
        )}
        <Button onClick={onClose}>Close</Button>
      </div>
      <ConfirmDialog
        open={deleting}
        title="Delete database"
        typed={database.slug}
        pending={remove.pending}
        error={remove.error}
        onConfirm={() => remove.run()}
        onClose={() => setDeleting(false)}
      >
        <span className="font-mono text-white">{database.slug}</span> and its data are removed. Backups already in the object store are not deleted.
      </ConfirmDialog>
    </div>
  );
}

function Part({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-4">
      <h3 className="text-sm font-medium text-graphite-200">{title}</h3>
      {children}
    </section>
  );
}
