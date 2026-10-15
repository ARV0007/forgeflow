<!-- stacks: static, react -->
# Timers, numbers and dates

## Countdowns and clocks that stay accurate
`setInterval(fn, 1000)` drifts and pauses in background tabs, so don't count ticks. Store the target time and recompute from the clock on every tick: `const left = target - Date.now();`. Always keep the id and `clearInterval(id)` when the countdown ends or the component unmounts.

## Splitting milliseconds
`const s = Math.floor(ms / 1000); const days = Math.floor(s / 86400); const hours = Math.floor(s % 86400 / 3600); const mins = Math.floor(s % 3600 / 60); const secs = s % 60;` Pad with `String(n).padStart(2, '0')`. Stop at zero instead of showing negative numbers.

## Money
Floating point can't represent 0.1 exactly, so `0.1 + 0.2` is `0.30000000000000004`. Round only for display, or do the arithmetic in cents. Format with `new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(amount)`, which handles separators and symbols for any locale and currency.

## Rounding
`Math.round(x * 100) / 100` rounds to two places; `toFixed(2)` returns a string for display. A tip or split calculator should handle an empty or zero input without showing NaN or Infinity.

## Dates
`new Date('2026-12-31')` is parsed as midnight UTC, which can show as the previous day in timezones west of Greenwich; `new Date(2026, 11, 31)` uses local time (months count from 0). Format with `toLocaleDateString(undefined, { dateStyle: 'medium' })` or `Intl.DateTimeFormat`.
