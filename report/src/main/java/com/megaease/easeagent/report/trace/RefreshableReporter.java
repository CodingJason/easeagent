package com.megaease.easeagent.report.trace;

import com.megaease.easeagent.report.OutputProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zipkin2.codec.Encoding;
import zipkin2.reporter.Reporter;
import zipkin2.reporter.SDKAsyncReporter;
import zipkin2.reporter.kafka11.KafkaSender;
import zipkin2.reporter.kafka11.SDKKafkaSender;
import zipkin2.reporter.kafka11.SimpleSender;

import java.util.concurrent.TimeUnit;

/**
 * RefreshableReporter is a reporter wrapper, which enhances the AgentAsyncReporter with refreshable function
 *
 * @param <S> always zipkin2.reporter
 */
public class RefreshableReporter<S> implements Reporter<S> {
    private final static Logger LOGGER = LoggerFactory.getLogger(RefreshableReporter.class);
    private final SDKAsyncReporter<S> asyncReporter;
    private final TraceProps traceProperties;
    private final OutputProperties agentOutputProperties;


    public RefreshableReporter(SDKAsyncReporter<S> reporter,
                               TraceProps traceProperties,
                               OutputProperties agentOutputProperties) {
        this.asyncReporter = reporter;
        this.traceProperties = traceProperties;
        this.agentOutputProperties = agentOutputProperties;
    }

    /**
     * report delegate span report to asyncReporter
     *
     * @param span a span need to be reported
     */
    @Override
    public void report(S span) {
        this.asyncReporter.report(span);
    }


    public synchronized void refresh() {
        if (asyncReporter.getSender() != null) {
            try {
                asyncReporter.getSender().close();
                asyncReporter.closeFlushThread();
            } catch (Exception e) {
//                LOGGER.warn("close sender error, ignore", e);
            }
        }

        if (traceProperties.getOutput().isEnabled() && traceProperties.isEnabled()) {
            final SDKKafkaSender sender = SDKKafkaSender.wrap(traceProperties,
                    KafkaSender.newBuilder()
                            .bootstrapServers(agentOutputProperties.getServers())
                            .topic(traceProperties.getOutput().getTopic())
                            .messageMaxBytes(traceProperties.getOutput().getMessageMaxBytes())
                            .encoding(Encoding.JSON)
                            .build());
            asyncReporter.setSender(sender);
            asyncReporter.setPending(traceProperties.getOutput().getQueuedMaxSpans(), traceProperties.getOutput().getQueuedMaxSize());
            asyncReporter.setMessageTimeoutNanos(messageTimeout(traceProperties.getOutput().getMessageTimeout(), TimeUnit.MILLISECONDS));
            asyncReporter.startFlushThread(); // start thread
        } else {
            asyncReporter.setSender(new SimpleSender());
        }
    }

    protected long messageTimeout(long timeout, TimeUnit unit) {
        if (timeout < 0) {
            timeout = 1000L;
        }
        if (unit == null) {
            unit = TimeUnit.MILLISECONDS;
        }
        return unit.toNanos(timeout);
    }
}
