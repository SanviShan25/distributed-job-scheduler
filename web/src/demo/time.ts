/** "2 hours 14 minutes", "1 hour", "45 minutes", "1 minute", "less than a minute". Rounds up to the minute. */
export function formatTimeLeft(ms: number): string {
  if (ms < 60_000) return "less than a minute";
  const totalMinutes = Math.ceil(ms / 60_000);
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  const h = hours === 1 ? "1 hour" : `${String(hours)} hours`;
  const m = minutes === 1 ? "1 minute" : `${String(minutes)} minutes`;
  if (hours === 0) return m;
  return minutes === 0 ? h : `${h} ${m}`;
}
