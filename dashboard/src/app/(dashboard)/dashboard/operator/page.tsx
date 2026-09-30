import type { Metadata } from "next";
import { OperatorConsole } from "./operator-console";

export const metadata: Metadata = { title: "Operator" };

export default async function OperatorPage({ searchParams }: PageProps<"/dashboard/operator">) {
  return <OperatorConsole view={(await searchParams).view} />;
}
