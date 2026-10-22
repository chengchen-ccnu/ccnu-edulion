/**
 * 为页面HTTP提供有界等待。run接收超时毫秒和可取消请求，返回原结果；
 * cancel中止全部在途请求，超时与取消分别抛错。取消不意味着服务端写入被回滚。
 */
export function createRequestScope() {
  const active = new Map<AbortController, () => void>();
  return {
    /** 以timeoutMs限制一次HTTP等待；send必须传递给定signal，正常返回原值，超时/取消抛错且释放计时器。 */
    async run<T>(timeoutMs: number, send: (signal: AbortSignal) => Promise<T>): Promise<T> {
      const controller = new AbortController();
      let timer: ReturnType<typeof setTimeout> | undefined;
      let abort: (() => void) | undefined;
      try {
        return await Promise.race([new Promise<T>((_, reject) => {
          abort = () => reject(new Error("CANCELLED"));
          controller.signal.addEventListener("abort", abort, { once: true });
          timer = setTimeout(() => { reject(new Error("TIMEOUT")); controller.abort(); }, timeoutMs);
          active.set(controller, () => clearTimeout(timer));
        }), send(controller.signal)]);
      } finally {
        clearTimeout(timer); if (abort) controller.signal.removeEventListener("abort", abort);
        active.delete(controller);
      }
    },
    /** 页面隐藏、离线、身份变化或卸载时调用；所有等待立即结束并释放定时器。 */
    cancel() { active.forEach((clear, controller) => { clear(); controller.abort(); }); active.clear(); }
  };
}
