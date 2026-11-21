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
  to `localhost` (the default).

By design, and not a vulnerability: the dashboard has no login. Bound to `0.0.0.0` or to a named
interface, anyone who can reach its port can read the run and stop it. Keep the default
`localhost` unless the network is trusted.

Nothing is bundled in the plugin jar. Jackson, Log4j and every other library come from JMeter's
own `lib/`, and are reported upstream.
