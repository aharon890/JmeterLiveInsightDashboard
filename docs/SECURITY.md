# Security policy

## Supported versions

Only the latest release receives fixes. Upgrade to it before reporting.

## Reporting a vulnerability

Please do **not** open a public issue. Use GitHub's private reporting instead:
**Security** tab → **Report a vulnerability**
(https://github.com/aharon890/JmeterLiveInsightDashboard/security/advisories/new).

Include the plugin version (the `Live Dashboard <version> started` line in `jmeter.log`, or
`pluginVersion` in `/api/status`), the JMeter and Java versions, the address the dashboard was
bound to (`live.dashboard.bind`), and the smallest test plan or request that shows the problem.
Strip anything sensitive first: sampler names, URLs and error messages often name internal hosts.

A confirmed issue is fixed in a patch release.

## Scope

The plugin serves a dashboard from inside the JMeter process and shows content taken from the
running test: sampler names from the plan, and URLs, response codes and response messages from
the system under test. In scope:

- script injection through that content, in the live page or in an exported snapshot;
- another website, open in the same browser, reading or stopping a run whose dashboard is bound
  to `localhost` (the default);
- a dashboard started with `live.dashboard.access.token` answering a request that does not
  carry the access link's token, on any bind.

By design, and not a vulnerability: the dashboard has no login unless it is asked for one. Bound
to `0.0.0.0` or to a named interface without `live.dashboard.access.token`, anyone who can reach
its port can read the run and stop it, and `jmeter.log` warns about that at start. With the
property, a request needs the token from the link on the start line, on every bind (see
Tutorial 10, "Example: Team Sharing"). The token is not encryption: someone who can read the
network traffic can read it. Keep the default `localhost` unless others need the dashboard.

Known limits of the access link, also by design:

- The link is written to `jmeter.log`. A token of your own that several runs share is known to
  whoever can read one run's log; a generated token ends with its JMeter process.
- An empty value counts as not set, because JMeter drops a property given as `-Jname=`.
- When JMeter opens the browser itself, the browser gets the link as a command-line argument,
  which other users of a shared machine can read. There, set
  `live.dashboard.open.browser=false` and open the link from the log.

Nothing is bundled in the plugin jar. Jackson, Log4j and every other library come from JMeter's
own `lib/`, and are reported upstream.
