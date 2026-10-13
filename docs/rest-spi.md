# Unified authorization contract and remaining host evidence

The implementation consumes the overlay-owned typed `RbacAuthorizer`, `RestAuthorizationResolver` and `McpAuthorizationResolver`. It maintains no copied host SPI sources. The three beans share authenticated subject/roles mapping and the configuration policy; the input/output resolver accepts the actual ProcessedFlow declaration.

REST search uses only a single `filters[namespace][EQUALS]` query leaf plus page/size/sort. A targeted test invokes the real host QueryFilterFormatBinder and asserts the generated NAMESPACE/EQUALS/value tuple. The complete query plan carries both LIST and READ; unrestricted IDs are required. Filtering fetched pages is never used.

For execution creation, the HTTP plan covers FLOW READ/EXECUTE plus the EXECUTION CREATE domain. The overlay's RbacExecutionGate must re-authorize the allocated execution ID and actual resolved flow, invoke the same dependency resolver, and authorize ACCESS_OUTPUTS whenever outputs are returned, **before** readExecutionInputs, checks, or queue writes. This activation point is required because a pre-route resolver cannot prove latest/raw source matches runtime revision, inherited namespace meta, checks and labels. The plugin currently accepts only the base create route with no options or strict wait=false/wait=true; revision, scheduleDate, kind, labels and breakpoints deny conservatively.

Bulk/asynchronous routes not resolved to a complete immutable operation plan deny. Unknown controller/method/path combinations deny. Execution file/metas additionally restrict canonical kestra URIs to the actual execution storage prefix and cannot access global file, namespace file, state, or another execution. Actual query execution, side effects, identity propagation, response rendering and image startup discovery remain deployment acceptance responsibilities.

The explicit route/handler/action coverage and administrator-visible 403 behavior are documented in [README](../README.md). Required deployment evidence is maintained in [validation evidence](validation.md). There is no broad SYSTEM/UI bypass.
