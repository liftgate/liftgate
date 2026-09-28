import type { Metadata } from "next";
import { safeNext } from "@/lib/util";
import { SsoSignIn } from "./sso-sign-in";

export const metadata: Metadata = { title: "SAML single sign-on" };

export default async function SsoPage({ searchParams }: PageProps<"/login/sso">) {
  return <SsoSignIn next={safeNext((await searchParams).next)} />;
}
