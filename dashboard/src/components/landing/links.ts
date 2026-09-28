export const repoUrl = "https://github.com/liftgate/liftgate";

export const sourceUrl = (path: string, from?: number, to?: number) => `${repoUrl}/blob/main/${path}${from ? `#L${from}-L${to}` : ""}`;
