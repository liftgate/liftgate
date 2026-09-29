"use client";

import { useState } from "react";
import { api } from "@/lib/api";
import { useAction, useApi, useRole } from "@/lib/hooks";
import type { CreatedInvitation, Member, OrgRole, User } from "@/lib/types";
import { formValues } from "@/lib/util";
import { Loaded } from "@/components/loaded";
import { PageHeader } from "@/components/page-header";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { CopyField } from "@/components/ui/copy-field";
import { Dialog } from "@/components/ui/dialog";
import { ErrorState } from "@/components/ui/empty-state";
import { Field, FormError, Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { PageSkeleton, TableSkeleton } from "@/components/ui/skeleton";
import { Cell, Row, Table } from "@/components/ui/table";

const roles: OrgRole[] = ["owner", "admin", "member"];

type Sent = CreatedInvitation & { role: string; email: string };

export function Members({ org }: { org: string }) {
  const { query: role, admin, owner } = useRole(org);
  const me = useApi<User>("/me");
  const members = useApi<Member[]>(`/orgs/${org}/members`);
  const [inviting, setInviting] = useState(false);
  const [sent, setSent] = useState<Sent>();
  const [removing, setRemoving] = useState<Member>();
  const leaving = !!removing && removing.user.id === me.data?.id;
  const invite = useAction(async (form: HTMLFormElement) => {
    const { role, email } = formValues(form);
    const created = await api<CreatedInvitation>(`/orgs/${org}/invitations`, { method: "POST", body: { role, email: email || null } });
    setSent({ ...created, role, email });
  });
  const changeRole = useAction(async (member: Member, role: string) => {
    await api(`/orgs/${org}/members/${member.user.id}`, { method: "PATCH", body: { role } });
    members.reload();
  });
  const remove = useAction(async (member: Member) => {
    await api(`/orgs/${org}/members/${leaving ? "me" : member.user.id}`, { method: "DELETE" });
    if (leaving) return window.location.replace("/dashboard");
    setRemoving(undefined);
    members.reload();
  });
  const closeInvite = () => {
    setInviting(false);
    setSent(undefined);
  };
  if (role.error) return <ErrorState error={role.error} retry={role.reload} />;
  if (role.loading) return <PageSkeleton />;
  return (
    <div className="flex flex-col gap-8">
      <PageHeader
        title="Members"
        description="Owners manage members, SSO and the organization. Admins deploy and manage projects, tokens and invitations. Members have read access."
        actions={
          admin && (
            <Button variant="primary" onClick={() => setInviting(true)}>
              Invite
            </Button>
          )
        }
      />
      <FormError message={changeRole.error} />
      <Loaded query={members} skeleton={<TableSkeleton />}>
        {(list) => (
          <Table columns={["Member", "Email", "Role", ""]}>
            {list.map((member) => {
              const self = member.user.id === me.data?.id;
              return (
                <Row key={member.user.id}>
                  <Cell className="font-medium">
                    {member.user.login}
                    {self && <span className="font-normal text-graphite-400"> (you)</span>}
                  </Cell>
                  <Cell className="text-graphite-400">{member.user.email}</Cell>
                  <Cell>
                    {owner ? (
                      <Select
                        aria-label={`Role of ${member.user.login}`}
                        value={member.role}
                        disabled={changeRole.pending}
                        onChange={(e) => changeRole.run(member, e.target.value)}
                      >
                        {roles.map((role) => (
                          <option key={role} value={role}>
                            {role}
                          </option>
                        ))}
                      </Select>
                    ) : (
                      <Badge>{member.role}</Badge>
                    )}
                  </Cell>
                  <Cell className="text-right">
                    {(self || owner) && (
                      <Button variant="danger" onClick={() => setRemoving(member)}>
                        {self ? "Leave" : "Remove"}
                      </Button>
                    )}
                  </Cell>
                </Row>
              );
            })}
          </Table>
        )}
      </Loaded>
      <Dialog open={inviting} title={sent ? "Share the invitation link" : "Invite someone"} onClose={closeInvite}>
        {sent ? (
          <div className="flex flex-col gap-4">
            <p className="text-sm text-graphite-200">
              Whoever opens this link joins {org} as {sent.role === "member" ? "a" : "an"} {sent.role}. It works once and expires on{" "}
              {new Date(sent.expiresAt).toLocaleDateString()}.{sent.emailed && ` We also emailed it to ${sent.email}.`}
            </p>
            <CopyField label="Invitation link" value={sent.url} />
            <div className="flex justify-end">
              <Button variant="primary" onClick={closeInvite}>
                Done
              </Button>
            </div>
          </div>
        ) : (
          <form
            onSubmit={(e) => {
              e.preventDefault();
              invite.run(e.currentTarget);
            }}
            className="flex flex-col gap-4"
          >
            <Field label="Role">
              <Select name="role" defaultValue="member">
                {roles
                  .filter((role) => owner || role !== "owner")
                  .map((role) => (
                    <option key={role} value={role}>
                      {role}
                    </option>
                  ))}
              </Select>
            </Field>
            <Field label="Email (optional)" hint="Liftgate emails the link when this instance can send mail. You can always copy it.">
              <Input name="email" type="email" maxLength={254} />
            </Field>
            <FormError message={invite.error} />
            <div className="flex justify-end gap-2">
              <Button onClick={closeInvite}>Cancel</Button>
              <Button type="submit" variant="primary" pending={invite.pending}>
                Create link
              </Button>
            </div>
          </form>
        )}
      </Dialog>
      <Dialog open={!!removing} title={leaving ? `Leave ${org}` : "Remove member"} onClose={() => setRemoving(undefined)}>
        {removing && (
          <div className="flex flex-col gap-4">
            <p className="text-sm text-graphite-200">
              {leaving ? (
                "You lose access to its projects and your API tokens for it stop working. An admin can invite you back."
              ) : (
                <>
                  <span className="font-medium text-white">{removing.user.login}</span> loses access to {org}, and the API tokens they created for it stop
                  working.
                </>
              )}
            </p>
            <FormError message={remove.error} />
            <div className="flex justify-end gap-2">
              <Button onClick={() => setRemoving(undefined)}>Cancel</Button>
              <Button variant="danger" pending={remove.pending} onClick={() => remove.run(removing)}>
                {leaving ? "Leave organization" : "Remove member"}
              </Button>
            </div>
          </div>
        )}
      </Dialog>
    </div>
  );
}
