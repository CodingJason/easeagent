/*
 * Copyright (c) 2017, MegaEase
 * All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.megaease.easeagent.zipkin.kafka.v2d3;

import brave.Span;
import brave.Tracer;
import brave.Tracing;
import com.megaease.easeagent.common.ContextCons;
import com.megaease.easeagent.core.interceptor.AgentInterceptor;
import com.megaease.easeagent.core.interceptor.AgentInterceptorChain;
import com.megaease.easeagent.core.interceptor.MethodInfo;
import com.megaease.easeagent.core.utils.AgentDynamicFieldAccessor;
import com.megaease.easeagent.core.utils.ContextUtils;
import com.megaease.easeagent.zipkin.kafka.brave.KafkaTracing;
import com.megaease.easeagent.zipkin.kafka.brave.MultiData;
import com.megaease.easeagent.zipkin.kafka.brave.TracingCallback;
import com.megaease.easeagent.zipkin.kafka.brave.TracingProducer;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.util.Map;

public class KafkaProducerTracingInterceptor implements AgentInterceptor {

    private final KafkaTracing kafkaTracing;

    public KafkaProducerTracingInterceptor(Tracing tracing) {
        this.kafkaTracing = KafkaTracing.newBuilder(tracing).remoteServiceName("kafka").build();
    }

    @Override
    public void before(MethodInfo methodInfo, Map<Object, Object> context, AgentInterceptorChain chain) {
        KafkaProducer<?, ?> producer = (KafkaProducer<?, ?>) methodInfo.getInvoker();
        String uri = AgentDynamicFieldAccessor.getDynamicFieldValue(producer);
        TracingProducer<?, ?> tracingProducer = kafkaTracing.producer(producer);
        ProducerRecord<?, ?> record = (ProducerRecord<?, ?>) methodInfo.getArgs()[0];
        MultiData<Span, Tracer.SpanInScope> multiData = tracingProducer.beforeSend(record, span -> span.tag("kafka.broker", uri));
        Callback callback = (Callback) methodInfo.getArgs()[1];
        Callback tracingCallback = TracingCallback.create(callback, multiData.data0, tracingProducer.currentTraceContext);
        methodInfo.getArgs()[1] = tracingCallback;
        context.put(MultiData.class, multiData);
        context.put(TracingProducer.class, tracingProducer);
        chain.doBefore(methodInfo, context);
    }

    @Override
    public Object after(MethodInfo methodInfo, Map<Object, Object> context, AgentInterceptorChain chain) {
        Boolean async = ContextUtils.getFromContext(context, ContextCons.ASYNC_FLAG);
        if (async != null && async) {
            return this.processAsync(methodInfo, context, chain);
        }
        return this.processSync(methodInfo, context, chain);
    }

    private Object processSync(MethodInfo methodInfo, Map<Object, Object> context, AgentInterceptorChain chain) {
        MultiData<Span, Tracer.SpanInScope> multiData = ContextUtils.getFromContext(context, MultiData.class);
        if (!methodInfo.isSuccess()) {
            multiData.data0.error(methodInfo.getThrowable()).finish();
        }
        multiData.data1.close();
        return chain.doAfter(methodInfo, context);
    }

    private Object processAsync(MethodInfo methodInfo, Map<Object, Object> context, AgentInterceptorChain chain) {
        TracingProducer<?, ?> tracingProducer = ContextUtils.getFromContext(context, TracingProducer.class);
        MultiData<Span, Tracer.SpanInScope> multiData = ContextUtils.getFromContext(context, MultiData.class);
        tracingProducer.afterSend(multiData.data1, multiData.data0, methodInfo.getThrowable());
        return chain.doAfter(methodInfo, context);
    }
}