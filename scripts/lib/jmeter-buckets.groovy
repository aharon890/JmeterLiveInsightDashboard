// D15 of the Docker gate: JMeter's numbers for every clock period, the reference for the
// Timeline's grouped points (scripts/lib/chart-vs-jmeter.mjs compares them).
//
//   java -cp "$JMETER_HOME/lib/*:$JMETER_HOME/lib/ext/*" groovy.ui.GroovyMain \
//        jmeter-buckets.groovy results.jtl <child-label-regex> <span-ms>[,<span-ms>...]
//
// A period is what JMeter's own over-time graphs plot: the samples whose END time falls in
// [kG, (k+1)G) on the epoch clock (TimeStampKeysSelector: time - time % granularity). The end
// is the sample's real end, as the dashboard takes it live (SampleResult.getEndTime): with the
// default sampleresult.timestamp.start=true the JTL holds the start, and a Transaction
// Controller that excludes timers reports them as idle time, so the end is start + elapsed +
// idle. (JMeter's HTML report rebuilds it as start + elapsed and so places such a transaction
// earlier by its timers: 600 ms for the smoke plan's checkout.) Each
// period's numbers come from JMeter's own calculator (SamplingStatCalculator, the class
// behind the Aggregate Report), fed that period's samples, not from a re-implementation.
// Rows are every label in the JTL; TOTAL is JMeter's live view, top-level samples only (the
// child regex names the sub-result labels), which is what the dashboard's TOTAL counts.
//
// Output: {"<span>": {"TOTAL": {"<k>": {...}}, "<label>": {"<k>": {...}}}}, with samples,
// errors, errorPct, mean, max and p95 for each period that has samples.
import groovy.json.JsonOutput
import org.apache.jmeter.reporters.ResultCollector
import org.apache.jmeter.samplers.SampleResult
import org.apache.jmeter.save.CSVSaveService
import org.apache.jmeter.util.JMeterUtils
import org.apache.jmeter.visualizers.SamplingStatCalculator
import org.apache.jmeter.visualizers.Visualizer

def home = System.getenv('JMETER_HOME') ?: '/opt/apache-jmeter-5.6.3'
JMeterUtils.setJMeterHome(home)
JMeterUtils.loadJMeterProperties(home + '/bin/jmeter.properties')
JMeterUtils.initLocale()

if (args.length != 3) { System.err.println('usage: jmeter-buckets.groovy <jtl> <child-regex> <span-ms>[,...]'); System.exit(2) }
def child = args[1] ? java.util.regex.Pattern.compile(args[1]) : null
def spans = args[2].split(',').collect { it.trim() as long }

// span -> row -> period start -> calculator
def periods = spans.collectEntries { [it, new TreeMap<String, TreeMap<Long, SamplingStatCalculator>>()] }
def startStamps = JMeterUtils.getPropDefault('sampleresult.timestamp.start', false)
def endOf = { SampleResult r -> startStamps ? r.startTime + r.time + r.idleTime : r.endTime }
def add = { long span, String row, SampleResult r ->
    long k = Math.floorDiv(endOf(r), span) * span
    periods[span].computeIfAbsent(row) { new TreeMap<Long, SamplingStatCalculator>() }
            .computeIfAbsent(k) { new SamplingStatCalculator(row) }.addSample(r)
}
def vis = [add: { SampleResult r ->
               spans.each { long span ->
                   add(span, r.sampleLabel, r)
                   if (child == null || !child.matcher(r.sampleLabel).find()) add(span, 'TOTAL', r)
               }
           },
           isStats: { -> true }] as Visualizer
CSVSaveService.processSamples(args[0], vis, new ResultCollector())

// The 95th percentile exactly as the Aggregate Report's column computes it (see
// jmeter-aggregate.groovy for why it is the float-widened value).
def p95 = ((Float.parseFloat(JMeterUtils.getPropDefault('aggregate_rpt_pct2', '95')) / 100f) as float) as double
def stats = { SamplingStatCalculator c ->
    [samples: c.count, errors: c.errorCount, errorPct: c.errorPercentage * 100, mean: c.mean,
     max: c.max.doubleValue(), p95: c.getPercentPoint(p95).doubleValue()]
}
println JsonOutput.toJson(periods.collectEntries { span, rows ->
    [String.valueOf(span), rows.collectEntries { row, ks -> [row, ks.collectEntries { k, c -> [String.valueOf(k), stats(c)] }] }]
})
