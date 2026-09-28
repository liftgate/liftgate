import { connection } from "next/server";

export const sessionCookie = "__Host-liftgate_session";

export async function landingEnabled() {
  await connection();
  return process.env.LIFTGATE_LANDING === "true";
}
