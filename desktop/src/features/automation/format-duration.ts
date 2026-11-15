const SECONDS_PER_MINUTE = 60;
const MINUTES_PER_HOUR = 60;

export function formatDuration(startedAt: string, finishedAt: string | null) {
  const end = finishedAt ? Date.parse(finishedAt) : Date.now();
  const totalSeconds = Math.max(0, Math.round((end - Date.parse(startedAt)) / 1000));
  const seconds = totalSeconds % SECONDS_PER_MINUTE;
  const totalMinutes = Math.floor(totalSeconds / SECONDS_PER_MINUTE);
  const pad = (value: number) => String(value).padStart(2, "0");
  if (totalMinutes < MINUTES_PER_HOUR) {
    return `${totalMinutes}:${pad(seconds)}`;
  }
  return `${Math.floor(totalMinutes / MINUTES_PER_HOUR)}:${pad(totalMinutes % MINUTES_PER_HOUR)}:${pad(seconds)}`;
}
