export const LOGIN_ROUTE_NAME = "login";
export const CURRENT_USER_QUERY_KEY = ["current-user"] as const;

export type AuthStatus = "unknown" | "anonymous" | "authenticated";
