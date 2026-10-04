import type { Metadata } from "next";
import { authError } from "@/lib/util";
import { Account } from "./account";

export const metadata: Metadata = { title: "Account" };

export default async function AccountPage({ searchParams }: PageProps<"/account">) {
  const { section, error } = await searchParams;
  return <Account section={typeof section === "string" ? section : undefined} error={authError(error)} />;
}
