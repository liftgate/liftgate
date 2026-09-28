export const repoUrl = "https://github.com/liftgate/liftgate";

export const sourceRef = "main";

export const chartVersion: string | null = null;

export const sourceUrl = (path: string, from?: number, to?: number) => `${repoUrl}/blob/${sourceRef}/${path}${from ? `#L${from}-L${to}` : ""}`;
