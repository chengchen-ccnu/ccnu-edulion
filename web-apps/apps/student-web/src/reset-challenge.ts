import type { IdentityType, VerificationChallenge } from "@edu/api-client";

/** 仅在当前标签页内暂存受控重置挑战；不写 URL、历史、localStorage 或 sessionStorage。 */
let pending: { challenge: VerificationChallenge; identityType: IdentityType; target: string } | null = null;

export function saveResetChallenge(challenge: VerificationChallenge, identityType: IdentityType, target: string) {
  pending = { challenge, identityType, target };
}

export function readResetChallenge() { return pending; }
export function clearResetChallenge() { pending = null; }
