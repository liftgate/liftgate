const sessionCookies = ["__Host-liftgate_session", "liftgate_session"];

export const ogImage = { url: "/og.jpg", width: 1200, height: 630, alt: "The Liftgate logo above the line Full-stack hosting. Open source." };

export const landingOn = (flag: string | undefined) => flag === "true";

export const hasSession = (has: (name: string) => boolean) => sessionCookies.some(has);

export const landingFor = (flag: string | undefined, has: (name: string) => boolean) => (landingOn(flag) ? { signedIn: hasSession(has) } : undefined);
