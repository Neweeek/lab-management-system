/**
 * 时间展示与输入转换的统一工具。
 *
 * ## 约定
 *
 * 后端所有时间列都保存**实验室本地墙钟时间**（Asia/Shanghai），格式为
 * `2026-09-17T10:30:00`，不带时区后缀。详见 backend 的 `LabTime`
 * 与 `db/migration/V1__baseline.sql` 顶部的时间约定。
 *
 * 因此前端有两条铁律：
 *
 * 1. **展示时不要换算时区。** 字符串里的数字就是实验室墙上时钟的读数，直接用。
 *    修复前 `projects.ts` 给时间串补了一个 `'Z'` 再按 Asia/Shanghai 格式化，
 *    而 `admin-reports.ts` 直接截断输出 —— 同一个 `created_at` 在两个页面
 *    相差 8 小时。现在统一走 `formatDateTime`。
 * 2. **输入框用本地墙钟。** `<input type="datetime-local">` / `type="date"` 的
 *    值本身就是本地墙钟，用 `toLocalInput` / `toLocalDateInput` 生成，
 *    不要经过 `Date.toISOString()`（那会先转成 UTC）。
 */

/** 把后端的存储时间串解析为本地墙钟时间。 */
export function parseStored(value: unknown): Date | null {
  if (value === null || value === undefined) return null;
  const text = String(value).trim();
  if (!text) return null;
  // 兼容 SQLite datetime() 产生的空格分隔格式，以及旧数据里可能缺少秒的写法。
  let normalized = text.includes(' ') ? text.replace(' ', 'T') : text;
  if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(normalized)) normalized += ':00';
  const parsed = new Date(normalized);
  return Number.isNaN(parsed.getTime()) ? null : parsed;
}

const pad = (value: number) => String(value).padStart(2, '0');

/**
 * 格式化存储时间为可读文本。
 *
 * @param value 后端返回的时间串
 * @param style `short` → `09-17 10:30`；`full` → `2026-09-17 10:30`（默认）
 * @param fallback 无法解析时的替代文本
 */
export function formatDateTime(value: unknown, style: 'short' | 'full' = 'full', fallback = '—'): string {
  const date = parseStored(value);
  if (!date) return fallback;
  const day = `${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
  const time = `${pad(date.getHours())}:${pad(date.getMinutes())}`;
  return style === 'short' ? `${day} ${time}` : `${date.getFullYear()}-${day} ${time}`;
}

/** 只要日期部分：`2026-09-17`。 */
export function formatDate(value: unknown, fallback = '—'): string {
  const date = parseStored(value);
  if (!date) return fallback;
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

/** 生成 `<input type="datetime-local">` 需要的本地墙钟值：`2026-09-17T10:30`。 */
export function toLocalInput(date: Date = new Date()): string {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** 生成 `<input type="date">` 需要的本地日期值：`2026-09-17`。 */
export function toLocalDateInput(date: Date = new Date()): string {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

/** 相对今天偏移若干天的本地日期值，用于默认值。 */
export function localDateFromToday(offsetDays: number): string {
  const date = new Date();
  date.setDate(date.getDate() + offsetDays);
  return toLocalDateInput(date);
}

/** 相对当前时刻偏移的本地墙钟值，用于预约表单默认值。 */
export function localDateTimeFromNow(offsetDays: number, offsetHours = 0): string {
  const date = new Date();
  date.setDate(date.getDate() + offsetDays);
  date.setHours(date.getHours() + offsetHours);
  date.setMinutes(0, 0, 0);
  return toLocalInput(date);
}
