# ReachAI Gateway Example Set

These copy-ready examples keep three security domains separate:

1. `/api/reachai/embed-token` stays behind normal business authentication and
   calls the Starter's `ReachAiEmbedTokenClient`.
2. `/api/reachai/embed/**` forwards the short-lived ReachAI Embed Bearer and
   must not run through the business OAuth2/JWT parser.
3. `/reachai/registry/**` forwards signed `X-ReachAI-*` callbacks to the
   Starter service. Ordinary business login may be bypassed for this exact
   path, but the Starter signature verifier remains mandatory.

Available examples:

- `spring-cloud-gateway/ReachAiEmbedProxySecurity.java`
- `spring-cloud-gateway/application-reachai-gateway.yml`
- `nginx/reachai.conf`
- `kong/kong.yml`

Replace hosts and service names with the real topology. Do not copy an example
without reconciling it with existing CORS, CSRF, global filters, rate limits and
network policies. Run `reachai-doctor --mode static` after applying it, then use
an authorized browser or `reachai-doctor --mode e2e` with business-supplied test
authorization.

