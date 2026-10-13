package io.kestra.extensions.rbac;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.async.propagation.ReactivePropagation;
import io.micronaut.core.propagation.PropagatedContext;
import io.micronaut.http.*;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.*;
import org.reactivestreams.Publisher;

@Filter("/**")
@Requires(property = "kestra.rbac.enabled", value = "true")
public final class RequestIdentityFilter implements HttpServerFilter {
    @Override public int getOrder() { return ServerFilterPhase.FIRST.order(); }
    @Override public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        var context = PropagatedContext.getOrEmpty().plus(new RequestIdentity(request));
        try (var scope = context.propagate()) {
            return ReactivePropagation.propagate(context, chain.proceed(request));
        }
    }
}
