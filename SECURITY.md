# Security policy

This SDK moves money: a vulnerability can cost real people real money. Please
report it privately, never in a public issue, pull request or discussion.

## Report a vulnerability

Use GitHub's private reporting: open the **Security** tab of the repository,
then **Report a vulnerability**
([direct link](https://github.com/Backaad/sdk-mobile-money/security/advisories/new)).
Only the maintainer sees the report.

Please include:

- the affected part (Java core, Spring Boot starter, Flutter package, demo) and
  version or commit;
- what an attacker can do, and the steps to reproduce it;
- a fix idea, if you have one.

Never include a real key, token, signature or customer data, not even from a
sandbox: describe them, or replace them with fake values.

## What to expect

The maintainer aims to acknowledge a report within 7 days, to agree with you on
a fix and a disclosure date, and to credit you in the advisory unless you prefer
not to be named. A fix is released before the details are made public.

## Scope

In scope: the code of this repository, including its handling of keys,
webhooks, idempotency and logs (see [docs/security.md](docs/security.md)).

Out of scope: the providers' own APIs and dashboards (report those to the
provider), and vulnerabilities in a merchant's application that do not come
from the SDK.

## Supported versions

No version has been released yet. Once `0.1.0` is out, security fixes go into
the latest release.
