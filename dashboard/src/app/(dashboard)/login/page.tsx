import type { Metadata } from "next";
import { authError, safeNext } from "@/lib/util";
import { SignIn } from "./sign-in";

export const metadata: Metadata = { title: "Sign in" };

export default async function LoginPage({ searchParams }: PageProps<"/login">) {
  const { next, error } = await searchParams;
  return <SignIn next={safeNext(next)} error={authError(error)} />;
}
