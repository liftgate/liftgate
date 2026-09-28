const sessionCookies = ["__Host-liftgate_session", "liftgate_session"];

export const landingOn = (flag: string | undefined) => flag === "true";

export const showsLanding = (flag: string | undefined, has: (name: string) => boolean) => landingOn(flag) && !sessionCookies.some(has);
