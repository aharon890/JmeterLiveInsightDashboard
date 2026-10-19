// One synthetic request for the perf harness (perf-harness.jmx).
//
// The sampler's Parameters, when set, is its fixed label (the transaction
// steps); otherwise the label is one of `labels` names, picked at random, so
// sampler cardinality is a single -J knob. Response times are lognormal with a
// per-label median, so every sampler has a different, long-tailed distribution
// and percentiles are worth checking. Every value written here lands in the
// JTL, which is the ground truth the dashboard is compared against.
import java.util.concurrent.ThreadLocalRandom

def r = ThreadLocalRandom.current()
def c = vars.getObject('perf.cfg')
if (c == null) {
    c = [labels: props.getProperty('labels', '30') as int,
         base  : props.getProperty('rt_base_ms', '50') as double,
         sigma : props.getProperty('rt_sigma', '0.6') as double,
         errPct: props.getProperty('error_pct', '2') as double,
         kinds : props.getProperty('error_kinds', '4') as int,
         bytes : props.getProperty('resp_bytes', '2048') as int,
         // rt_uniform_max_ms > 0: response times uniform in [0, max) instead of lognormal.
         // rt_fake=1: record the response time without sleeping it (the sample's
         // start is back-dated), so wide distributions need no army of threads.
         umax  : props.getProperty('rt_uniform_max_ms', '0') as long,
         fake  : props.getProperty('rt_fake', '0') == '1']
    vars.putObject('perf.cfg', c)
}

String label = Parameters?.trim()
int i
if (label) {
    i = Math.abs(label.hashCode() % 5)
} else {
    i = r.nextInt(c.labels)
    label = 'req-' + String.format('%04d', i)
}
SampleResult.setSampleLabel(label)

long rt = 0
if (c.umax > 0) {
    rt = r.nextLong(c.umax)
} else if (c.base > 0) {
    double median = c.base * (1 + (i % 5) * 0.25)
    rt = Math.round(median * Math.exp(c.sigma * r.nextGaussian()))
}
if (rt > 0) {
    // setStartTime is protected; Groovy calls it anyway. JSR223's sampleEnd()
    // then measures rt from the back-dated start.
    if (c.fake) SampleResult.setStartTime(System.currentTimeMillis() - rt)
    else Thread.sleep(rt)
}

def CODES = ['500', '502', '503', '504', '404', '429', '400', '401', '403', '409']
if (r.nextDouble() * 100 < c.errPct) {
    int k = r.nextInt(c.kinds)
    SampleResult.setSuccessful(false)
    SampleResult.setResponseCode(k < CODES.size() ? CODES[k] : String.valueOf(900 + k))
    SampleResult.setResponseMessage('Synthetic failure ' + k)
} else {
    SampleResult.setSuccessful(true)
    SampleResult.setResponseCode('200')
    SampleResult.setResponseMessage('OK')
}
SampleResult.setBytes((long) (c.bytes + r.nextInt(512)))
SampleResult.setSentBytes((long) (150 + r.nextInt(100)))
SampleResult.setLatency(Math.round(rt * 0.7))
SampleResult.setConnectTime(rt > 0 ? r.nextInt(3) : 0)

// A non-null return value would become the response body and change the
// byte count JMeter reports.
return null
