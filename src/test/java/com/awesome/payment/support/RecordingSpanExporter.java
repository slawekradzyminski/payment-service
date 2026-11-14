package com.awesome.payment.support;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Keeps every exported span in memory, in place of the OTLP exporter. */
public class RecordingSpanExporter implements SpanExporter {

    private final List<SpanData> spans = new CopyOnWriteArrayList<>();

    @Override
    public CompletableResultCode export(Collection<SpanData> batch) {
        spans.addAll(batch);
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }

    public List<SpanData> spans() {
        return List.copyOf(spans);
    }

    public List<SpanData> spans(SpanKind kind) {
        return spans.stream().filter(span -> span.getKind() == kind).toList();
    }

    public List<SpanData> named(String name) {
        return spans.stream().filter(span -> span.getName().equals(name)).toList();
    }

    public void clear() {
        spans.clear();
    }
}
