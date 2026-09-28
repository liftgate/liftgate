import type { Metadata } from "next";
import { authError } from "@/lib/util";
import { Account } from "./account";

export const metadata: Metadata = { title: "Account" };

export default async function AccountPage({ searchParams }: PageProps<"/account">) {
  return <Account error={authError((await searchParams).error)} />;
}
