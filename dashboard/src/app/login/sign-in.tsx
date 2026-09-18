"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { api, ApiError, describe } from "@/lib/api";
import { useAction, useApi } from "@/lib/hooks";
import type { AuthProviders } from "@/lib/types";
import { formValues } from "@/lib/util";
import { getPasskey, isAbort } from "@/lib/webauthn";
import { Loaded } from "@/components/loaded";
import { PageHeader } from "@/components/page-header";
import { ProviderLink, providerNames } from "@/components/provider";
import { Button, buttonClasses } from "@/components/ui/button";
import { Field, FormError, Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";

const signInWithPasskey = async (mediation?: CredentialMediationRequirement, signal?: AbortSignal) => {
  const options = await api<{ publicKey: PublicKeyCredentialRequestOptionsJSON }>("/auth/passkey/options", { method: "POST" });
  const credential = await getPasskey(options, mediation, signal);
  await api("/auth/passkey/verify", { method: "POST", body: { credential } });
};

const startEmail = (email: string) => api("/auth/email/start", { method: "POST", body: { email } });

export function SignIn({ next, error }: { next: string; error?: string }) {
  const providers = useApi<AuthProviders>("/auth/providers");
  const [email, setEmail] = useState<string>();
  if (email) return <CodeStep email={email} next={next} onBack={() => setEmail(undefined)} />;
  return (
    <>
      <PageHeader centered title="Sign in to Liftgate" description="Sign in or create an account with any method below." />
      <Loaded
        query={providers}
        skeleton={
          <div className="flex flex-col gap-4">
            <Skeleton className="h-8" />
            <Skeleton className="h-8" />
            <Skeleton className="h-8" />
          </div>
        }
      >
        {(list) => <Methods providers={list} next={next} error={error} onCodeSent={setEmail} />}
      </Loaded>
    </>
  );
}

function Methods({ providers, next, error, onCodeSent }: { providers: AuthProviders; next: string; error?: string; onCodeSent: (email: string) => void }) {
  const autofill = useRef<AbortController>(null);
  const [autofillError, setAutofillError] = useState<string>();
  useEffect(() => {
    if (!providers.passkey) return;
    const controller = new AbortController();
    autofill.current = controller;
    const listen = async (retry: boolean): Promise<void> => {
      try {
        await signInWithPasskey("conditional", controller.signal);
        window.location.assign(next);
      } catch (e) {
        if (retry && e instanceof ApiError && e.code === "invalid_passkey") return listen(false);
        if (!isAbort(e)) setAutofillError(describe(e));
      }
    };
    window.PublicKeyCredential?.isConditionalMediationAvailable?.().then((available) => (available ? listen(true) : undefined));
    return () => controller.abort();
  }, [providers.passkey, next]);
  const passkey = useAction(async () => {
    autofill.current?.abort();
    await signInWithPasskey();
    window.location.assign(next);
  });
  const start = useAction(async (form: HTMLFormElement) => {
    const { email } = formValues(form);
    await startEmail(email);
    onCodeSent(email);
  });
  const alternatives = providers.email || providers.passkey || providers.sso;
  return (
    <div className="flex flex-col gap-4">
      {providers.oauth.map((provider) => (
        <ProviderLink key={provider} provider={provider} intent="signin" next={next} className="w-full">
          Continue with {providerNames[provider]}
        </ProviderLink>
      ))}
      {providers.oauth.length > 0 && alternatives && (
        <div className="flex items-center gap-4 text-xs text-graphite-400">
          <span className="h-px flex-1 bg-graphite-700" />
          or
          <span className="h-px flex-1 bg-graphite-700" />
        </div>
      )}
      {providers.email && (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            start.run(e.currentTarget);
          }}
          className="flex flex-col gap-2"
        >
          <Field label="Email">
            <Input name="email" type="email" required autoComplete="username webauthn" placeholder="you@company.com" />
          </Field>
          <Button type="submit" variant="primary" pending={start.pending} className="w-full">
            Continue with email
          </Button>
        </form>
      )}
      {providers.passkey && (
        <Button pending={passkey.pending} onClick={() => passkey.run()} className="w-full">
          Continue with passkey
        </Button>
      )}
      {providers.sso && (
        <Link href={`/login/sso?${new URLSearchParams({ next })}`} className={buttonClasses("ghost", "w-full")}>
          Continue with SAML SSO
        </Link>
      )}
      <FormError message={start.error ?? passkey.error ?? autofillError ?? error} />
    </div>
  );
}

function CodeStep({ email, next, onBack }: { email: string; next: string; onBack: () => void }) {
  const [code, setCode] = useState("");
  const [resent, setResent] = useState(false);
  const verify = useAction(async (value: string) => {
    try {
      await api("/auth/email/verify", { method: "POST", body: { email, code: value } });
    } catch (e) {
      setCode("");
      throw e;
    }
    window.location.assign(next);
  });
  const resend = useAction(async () => {
    setResent(false);
    await startEmail(email);
    setCode("");
    setResent(true);
  });
  return (
    <>
      <PageHeader
        centered
        title="Check your email"
        description={
          <>
            Enter the 6-digit code sent to <span className="text-white">{email}</span>. It expires in 10 minutes.
          </>
        }
      />
      <form
        onSubmit={(e) => {
          e.preventDefault();
          verify.run(code);
        }}
        className="flex flex-col gap-4"
      >
        <Field label="Verification code">
          <Input
            value={code}
            onChange={(e) => {
              const digits = e.target.value.replace(/\D/g, "").slice(0, 6);
              setCode(digits);
              if (digits.length === 6 && !verify.pending) verify.run(digits);
            }}
            required
            autoFocus
            readOnly={verify.pending}
            pattern="\d{6}"
            inputMode="numeric"
            autoComplete="one-time-code"
            className="text-center font-mono tracking-[0.5em]"
          />
        </Field>
        <Button type="submit" variant="primary" pending={verify.pending} className="w-full">
          Verify code
        </Button>
        <FormError message={verify.error ?? resend.error} />
        {resent && (
          <p role="status" className="text-sm text-graphite-400">
            A new code is on its way. Earlier codes no longer work.
          </p>
        )}
        <div className="flex justify-between gap-2">
          <Button variant="ghost" onClick={onBack}>
            Use another email
          </Button>
          <Button variant="ghost" pending={resend.pending} onClick={() => resend.run()}>
            Send a new code
          </Button>
        </div>
      </form>
    </>
  );
}
