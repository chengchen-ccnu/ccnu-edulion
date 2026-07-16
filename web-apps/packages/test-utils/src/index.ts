export const mockGatewayUrl = "http://127.0.0.1:4010";

export function apiUrl(path: string): string {
  return new URL(path, `${mockGatewayUrl}/`).toString();
}
