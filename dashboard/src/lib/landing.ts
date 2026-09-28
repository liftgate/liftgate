import { connection } from "next/server";

export const sessionCookies = ["__Host-liftgate_session", "liftgate_session"];

export async function landingEnabled() {
  await connection();
  return process.env.LIFTGATE_LANDING === "true";
}
