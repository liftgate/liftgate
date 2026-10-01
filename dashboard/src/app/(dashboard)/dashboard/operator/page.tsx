import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { cache } from "react";
import { apiStatus } from "@/lib/api-status";
import { OperatorConsole } from "./operator-console";

const hidden = cache(async () => ((await apiStatus("/operator/summary")) ?? 200) !== 200);

export async function generateMetadata(): Promise<Metadata> {
  return { title: (await hidden()) ? "Page not found" : "Operator" };
}

export default async function OperatorPage({ searchParams }: PageProps<"/dashboard/operator">) {
  if (await hidden()) notFound();
  return <OperatorConsole view={(await searchParams).view} />;
}
