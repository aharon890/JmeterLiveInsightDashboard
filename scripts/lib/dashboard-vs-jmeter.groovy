// D13 of the Docker gate: the dashboard's final numbers against JMeter's own.
//
//   java -cp "$JMETER_HOME/lib/*" groovy.ui.GroovyMain \
//        dashboard-vs-jmeter.groovy <dashboard-catchup.json> <jmeter-aggregate.json>
//
// The plugin is a reporter, and JMeter's Aggregate Report is the reference for every
// number it shows. jmeter-aggregate.json is that reference: JMeter's own
// SamplingStatCalculator (the class behind the Aggregate and Summary Reports) run over
// the same run's results.jtl by scripts/perf/jmeter-aggregate.groovy, which gives two
// views, and each half of the dashboard matches one of them:
//   TOTAL  the "live" view: top-level samples only, as JMeter's live Aggregate Report
//          counts them. A transaction that generates a parent sample counts once.
//   rows   the "file" view: one row per label in the JTL, the transaction's steps
//          included. The dashboard shows those steps as rows of their own, and every
//          other row is the same in both views.
//
// Before this check, the only layer with real JMeter compared one number with JMeter
// (a distributed run's total count), and every wrong-number bug in this repo's history
// was found by hand.
//
// Exact: counts, errors, min, max and percentiles. Every smoke-plan sample is far under
// 10 s, where the dashboard's percentiles are exact by design. Within rounding (the
// wire carries 7 significant digits): mean, error %, throughput and KB/s.
// Exit 0 when everything matches, 1 on any mismatch, 2 when an input is unusable.
import groovy.json.JsonSlurper

def die = { String msg -> System.err.println("D13: " + msg); System.exit(2) }
if (args.length != 2) die('usage: dashboard-vs-jmeter.groovy <catchup.json> <jmeter-aggregate.json>')

def dash = null, ref = null
try { dash = new JsonSlurper().parse(new File(args[0])) } catch (e) { die("unreadable dashboard catch-up: ${e.message}") }
try {
    def agg = new JsonSlurper().parse(new File(args[1]))
    ref = [total: agg?.live?.total, samplers: agg?.file?.samplers]
} catch (e) { die("unreadable JMeter aggregate: ${e.message}") }
if (!(dash instanceof Map) || !ref.total || !ref.samplers) die('an input is empty')
if (dash.testRunning != false) die('the catch-up was taken while the test was still running')

// samplerStats travels columnar: {cols: [...], rows: {name: [...]}} (Columnar.encode)
def decode = { m -> m?.cols ? m.rows.collectEntries { k, r -> [k, [m.cols, r].transpose().collectEntries()] } : (m ?: [:]) }
def samplers = decode(dash.samplerStats)

def EXACT = true, ROUNDED = false
// dashboard field : [JMeter field, exact?]
def TOTAL_FIELDS = [totalSamples: ['samples', EXACT], totalErrors: ['errors', EXACT],
                    overallErrorRate: ['errorPct', ROUNDED], avgResponseTime: ['mean', ROUNDED],
                    minResponseTime: ['min', EXACT], maxResponseTime: ['max', EXACT],
                    p50: ['median', EXACT], p90: ['p90', EXACT], p95: ['p95', EXACT], p99: ['p99', EXACT],
                    overallThroughput: ['throughput', ROUNDED],
                    receivedKBs: ['receivedKBs', ROUNDED], sentKBs: ['sentKBs', ROUNDED]]
def SAMPLER_FIELDS = [samples: ['samples', EXACT], errors: ['errors', EXACT],
                      errorRate: ['errorPct', ROUNDED], avgResponseTime: ['mean', ROUNDED],
                      minResponseTime: ['min', EXACT], maxResponseTime: ['max', EXACT],
                      p50: ['median', EXACT], p90: ['p90', EXACT], p95: ['p95', EXACT], p99: ['p99', EXACT],
                      throughput: ['throughput', ROUNDED],
                      receivedKBs: ['receivedKBs', ROUNDED], sentKBs: ['sentKBs', ROUNDED]]

def mismatches = []
int compared = 0
def same = { got, want, boolean exact ->
    if (!(got instanceof Number) || !(want instanceof Number)) return false
    double g = got as double, w = want as double
    exact ? g == w : Math.abs(g - w) <= Math.max(1e-6 * Math.abs(w), 1e-9)
}
def compareRow = { String row, Map got, Map want, Map fields ->
    fields.each { String dashField, spec ->
        compared++
        def g = got?.get(dashField), w = want?.get(spec[0])
        if (!same(g, w, spec[1])) mismatches << [row, dashField, g, w]
    }
}

compareRow('TOTAL', dash.cumulativeStats as Map, ref.total as Map, TOTAL_FIELDS)
def names = (samplers.keySet() + (ref.samplers as Map).keySet()) as TreeSet
names.each { String name -> compareRow(name, samplers[name] as Map, ref.samplers[name] as Map, SAMPLER_FIELDS) }

println "D13: compared ${compared} values in ${names.size() + 1} rows (TOTAL + ${names.size()} samplers)"
if (!mismatches) {
    println "D13: every value equals JMeter's Aggregate Report"
    System.exit(0)
}
println "D13: ${mismatches.size()} value(s) differ from JMeter's Aggregate Report:"
mismatches.each { row, field, g, w -> println "  ${row} :: ${field}  dashboard=${g}  jmeter=${w}" }
System.exit(1)
