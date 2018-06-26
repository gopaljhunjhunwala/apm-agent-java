/*-
 * #%L
 * Elastic APM Java agent
 * %%
 * Copyright (C) 2018 Elastic and contributors
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package co.elastic.apm.servlet;

import co.elastic.apm.bci.ElasticApmInstrumentation;
import co.elastic.apm.bci.VisibleForAdvice;
import co.elastic.apm.impl.ElasticApmTracer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import javax.annotation.Nullable;
import javax.servlet.AsyncContext;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;

import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.nameContains;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/**
 * Only the methods annotated with {@link Advice.OnMethodEnter} and {@link Advice.OnMethodExit} may contain references to
 * {@code javax.servlet}, as these are inlined into the matching methods.
 * The agent itself does not have access to the Servlet API classes, as they are loaded by a child class loader.
 * See https://github.com/raphw/byte-buddy/issues/465 for more information.
 */
public class AsyncInstrumentation extends ElasticApmInstrumentation {

    public static final String SERVLET_API_ASYNC_GROUP_NAME = "servlet-api-async";

    @Override
    public void init(ElasticApmTracer tracer) {
        StartAsyncAdvice.init(tracer);
    }

    @Override
    public ElementMatcher<? super TypeDescription> getTypeMatcher() {
        return not(isInterface())
            .and(nameContains("Request"))
            .and(hasSuperType(named("javax.servlet.http.HttpServletRequest")));
    }

    /**
     * Matches
     * <ul>
     * <li>{@link HttpServletRequest#startAsync()}</li>
     * <li>{@link HttpServletRequest#startAsync(ServletRequest, ServletResponse)}</li>
     * </ul>
     *
     * @return
     */
    @Override
    public ElementMatcher<? super MethodDescription> getMethodMatcher() {
        return isPublic()
            .and(named("startAsync"))
            .and(returns(named("javax.servlet.AsyncContext")))
            .and(takesArguments(0)
                .or(
                    takesArgument(0, named("javax.servlet.ServletRequest"))
                        .and(takesArgument(1, named("javax.servletServletResponse")))
                )
            );
    }

    @Override
    public String getInstrumentationGroupName() {
        return SERVLET_API_ASYNC_GROUP_NAME;
    }

    @Override
    public Class<?> getAdviceClass() {
        return StartAsyncAdvice.class;
    }

    public static class StartAsyncAdvice {
        @VisibleForAdvice
        public static final String ASYNC_LISTENER_ADDED = ServletApiAdvice.class.getName() + ".asyncListenerAdded";
        @Nullable
        @VisibleForAdvice
        public static ServletTransactionHelper servletTransactionHelper;

        static void init(ElasticApmTracer tracer) {
            servletTransactionHelper = new ServletTransactionHelper(tracer);
        }

        @Advice.OnMethodExit
        private static void onExitStartAsync(@Advice.Return AsyncContext asyncContext) {
            final ServletRequest request = asyncContext.getRequest();
            if (request.getAttribute(ASYNC_LISTENER_ADDED) != null) {
                return;
            }
            if (servletTransactionHelper != null &&
                request.getAttribute(ServletApiAdvice.TRANSACTION_ATTRIBUTE) != null &&
                request.getAttribute(ASYNC_LISTENER_ADDED) == null) {
                // makes sure that the listener is only added once, even if the request is wrapped
                // which leads to multiple invocations of startAsync for the same underlying request
                request.setAttribute(ASYNC_LISTENER_ADDED, Boolean.TRUE);
                asyncContext.addListener(new ApmAsyncListener());
            }
        }

    }

}