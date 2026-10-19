// JMeter's own Aggregate Report numbers for a JTL, computed by JMeter's own
// classes (SamplingStatCalculator, the calculator behind the Aggregate and
// Summary Reports) rather than by a re-implementation of them.
//
//   java -cp "$JMETER_HOME/lib/*:$JMETER_HOME/lib/ext/*" groovy.ui.GroovyMain \
//        jmeter-aggregate.groovy results.jtl [child-label-regex]
//
// Two views, because JMeter itself shows two: loading a CSV JTL into the
// Aggregate Report (or the HTML report) sees every row, sub-results included,
// since CSV flattens them; the LIVE Aggregate Report only ever receives
// top-level results, so transaction children never reach it. The child regex
// names the sub-result labels so the second view can be rebuilt from the file.
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

def jtl = args[0]
def child = args.length > 1 ? java.util.regex.Pattern.compile(args[1]) : null

def views = [file: [rows: new TreeMap<String, SamplingStatCalculator>(), total: new SamplingStatCalculator('TOTAL')],
             live: [rows: new TreeMap<String, SamplingStatCalculator>(), total: new SamplingStatCalculator('TOTAL')]]

def add = { view, SampleResult r ->
    view.rows.computeIfAbsent(r.sampleLabel) { new SamplingStatCalculator(it) }.addSample(r)
    view.total.addSample(r)
}
def vis = [add: { SampleResult r ->
               add(views.file, r)
               if (child == null || !child.matcher(r.sampleLabel).find()) add(views.live, r)
           },
           isStats: { -> true }] as Visualizer
CSVSaveService.processSamples(jtl, vis, new ResultCollector())

// The Aggregate Report parses its 90/95/99 column percents as Float/100
// (StatGraphVisualizer), so 0.9 is really 0.89999998 and exact .5 ties round
// down. Pass the same float-widened values to see what its columns show.
// Groovy divides two floats in double (90f / 100f == 0.9 exactly); Java's
// float division gives 0.9f, i.e. 0.89999998 widened. Cast back to float to
// get Java's value: it decides the rank whenever n x percent ends in .5.
def pf = { String prop, String dflt -> ((Float.parseFloat(JMeterUtils.getPropDefault(prop, dflt)) / 100f) as float) as double }
def (q1, q2, q3) = [pf('aggregate_rpt_pct1', '90'), pf('aggregate_rpt_pct2', '95'), pf('aggregate_rpt_pct3', '99')]
def row = { SamplingStatCalculator c ->
    [samples: c.count, errors: c.errorCount, errorPct: c.errorPercentage * 100,
     mean: c.mean, median: c.median.doubleValue(),
     p90: c.getPercentPoint(q1).doubleValue(), p95: c.getPercentPoint(q2).doubleValue(),
     p99: c.getPercentPoint(q3).doubleValue(),
     min: c.min.doubleValue(), max: c.max.doubleValue(), stdDev: c.standardDeviation,
     throughput: c.rate, receivedKBs: c.KBPerSecond, sentKBs: c.sentKBPerSecond]
}
def result = [:]
views.each { name, v ->
    result[name] = [total: row(v.total), samplers: v.rows.collectEntries { k, c -> [k, row(c)] }]
}
println JsonOutput.toJson(result)
