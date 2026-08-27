/** 共享播放器发出的领域中立进度样本；业务页面负责决定是否以及向哪个领域持久化。 */
export interface PlaybackProgressSample {
  positionSeconds: number;
  watchedSeconds: number;
  completed: boolean;
}

export interface PlaybackProgressTracker {
  play(positionSeconds: number): void;
  observe(positionSeconds: number): void;
  pause(positionSeconds: number): void;
  complete(positionSeconds: number): void;
  flush(): void;
  reset(): void;
}

/**
 * 按真实播放墙钟时间累计观看秒数，拖动进度条不会虚增观看时长。
 * 默认每累计 15 秒或暂停/结束时产生一次有界样本；页面卸载可 flush 后立即释放播放器资源。
 */
export function createPlaybackProgressTracker(
    emit: (sample: PlaybackProgressSample) => void,
    clock: () => number = () => performance.now(), intervalSeconds = 15): PlaybackProgressTracker {
  let playingSince: number | null = null;
  let watchedMilliseconds = 0;
  let positionSeconds = 0;
  let lastReportedPosition = 0;

  function accumulate(): void {
    if (playingSince === null) return;
    const current = clock();
    watchedMilliseconds += Math.max(0, Math.min(300_000, current - playingSince));
    playingSince = current;
  }

  function report(completed: boolean, force: boolean): void {
    const watchedSeconds = Math.min(300, Math.floor(watchedMilliseconds / 1_000));
    if (!force && watchedSeconds < intervalSeconds) return;
    if (!completed && watchedSeconds === 0 && Math.floor(positionSeconds) === Math.floor(lastReportedPosition)) return;
    emit({ positionSeconds, watchedSeconds, completed });
    watchedMilliseconds -= watchedSeconds * 1_000;
    lastReportedPosition = positionSeconds;
  }

  return {
    play(position) {
      positionSeconds = Math.max(0, position);
      if (playingSince === null) playingSince = clock();
    },
    observe(position) {
      accumulate();
      positionSeconds = Math.max(0, position);
      report(false, false);
    },
    pause(position) {
      accumulate();
      playingSince = null;
      positionSeconds = Math.max(0, position);
      report(false, true);
    },
    complete(position) {
      accumulate();
      playingSince = null;
      positionSeconds = Math.max(0, position);
      report(true, true);
    },
    flush() {
      accumulate();
      playingSince = null;
      report(false, true);
    },
    reset() {
      playingSince = null;
      watchedMilliseconds = 0;
      positionSeconds = 0;
      lastReportedPosition = 0;
    }
  };
}
