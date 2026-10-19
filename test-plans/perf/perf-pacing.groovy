// Pacing and bursts for the perf harness (a JSR223 Timer: the value of the
// last expression is the delay in ms before the next sample).
//
// rate             samples/s for the whole thread group; 0 = as fast as possible
// rate_steps       e.g. "2000,5000,10000": overrides rate, stepping through the
//                  list every step_s seconds and holding the last value
// burst_period_ms  > 0: alternate "on" and "off" windows of this length
//
// Each thread paces itself (interval = threads / rate), so there is no shared
// state for threads to contend on, which matters at the high-rate end. A thread
// that falls behind does not burst to catch up.
long now = System.currentTimeMillis()
long rate = props.getProperty('rate', '0') as long
long period = props.getProperty('burst_period_ms', '0') as long

String stepsProp = props.getProperty('rate_steps', '')
if (stepsProp) {
    def steps = vars.getObject('perf.steps')
    if (steps == null) {
        steps = stepsProp.split(',')*.trim()*.toLong()
        vars.putObject('perf.steps', steps)
    }
    long t0 = (props.putIfAbsent('perf.t0', now) ?: now) as long
    long stepMs = (props.getProperty('step_s', '150') as long) * 1000
    rate = steps[(int) Math.min((now - t0).intdiv(stepMs), steps.size() - 1)]
}

double due = now
if (rate > 0) {
    double interval = ctx.getThreadGroup().getNumThreads() * 1000.0 / rate
    Double next = vars.getObject('perf.next')
    due = next == null ? now : Math.max(next, (double) now)
    vars.putObject('perf.next', due + interval)
}
long delay = Math.max(0L, Math.round(due - now))

if (period > 0) {
    long phase = (now + delay) % (2 * period)
    if (phase >= period) delay += 2 * period - phase
}
delay
